package com.graebert.storage.main;

import com.amazonaws.regions.Regions;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.plugins.EC2Plugin;
import com.amazonaws.xray.strategy.LogErrorContextMissingStrategy;
import com.amazonaws.xray.strategy.sampling.LocalizedSamplingStrategy;
import com.google.common.net.InternetDomainName;
import com.graebert.storage.config.ConfigObject;
import com.graebert.storage.config.CustomProperties;
import com.graebert.storage.config.Properties;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.config.ServerConfigBuilder;
import com.graebert.storage.config.ServerConfigParser;
import com.graebert.storage.integration.OneDrive;
import com.graebert.storage.integration.Onshape;
import com.graebert.storage.integration.hancom.Hancom;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.logs.XRayLogger;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.storage.UsersList;
import com.graebert.storage.subscriptions.NotifyUser;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.vertx.Periodic;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.dropwizard.MetricsService;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/*
    ----- Vertx 4.0.3 -----
    * VertxOptions doesn't have its isClustered() anymore, using Vertx.vertx(options).isClustered() instead
    * Future interface updated - Using Promise (instead of Future) for complete() and fail(), and later use its future() to
      get the Future object.
    * Removed CookieHandler, as cookies are enabled by default and this handler is not required anymore

*/

public class Server extends AbstractVerticle {
  static {
    // init Xray
    try {
      AWSXRayRecorderBuilder builder = AWSXRayRecorderBuilder.standard()
          .withSegmentListener(new XRayLogger())
          .withContextMissingStrategy(new LogErrorContextMissingStrategy())
          .withSamplingStrategy(
              new LocalizedSamplingStrategy(Server.class.getResource("/sampling-rules.json")));

      if (Regions.getCurrentRegion() != null) {
        builder = builder.withPlugin(new EC2Plugin());
      }

      AWSXRay.setGlobalRecorder(builder.build());
    } catch (Exception ignore) {
    }
  }

  public static void main(String[] args) {
    String theClass = Server.class.getName();
    String dir = Server.class.getPackage().getName().replace(".", "/");
    // Smart cwd detection
    // Based on the current directory (.) and the desired directory (exampleDir), we try to compute
    // the vertx.cwd
    // directory:
    try {
      // We need to use the canonical file. Without the file name is .
      File current = new File(".").getCanonicalFile();
      if (dir.startsWith(current.getName()) && !dir.equals(current.getName())) {
        dir = dir.substring(current.getName().length() + 1);
      }
      //            System.setProperty("javax.net.ssl.trustStore", new
      // File("./fiddlerKeyStore").getCanonicalFile().getAbsolutePath());
      //            System.setProperty("javax.net.ssl.trustStorePassword", "fiddler");
    } catch (IOException ignore) {
    }
    System.setProperty("vertx.cwd", dir);
    Consumer<Vertx> runner = vertx -> {
      try {
        vertx.deployVerticle(theClass);
      } catch (Throwable t) {
        DynamoBusModBase.log.error("Error in deploying server class : " + t);
      }
    };
    VertxOptions options = new VertxOptions();
    Launcher.updateVertxOptions(options);
    Vertx vertx = Vertx.vertx(options);
    runner.accept(vertx);
    // AS: we can set up clustered vertx using Vertx.clusteredVertx()
  }

  private JsonObject getInternalConfig() {
    InputStream is = Server.class.getResourceAsStream("/config.json");
    if (is == null) {
      return new JsonObject();
    }
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();
    String line;
    try {
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
    } catch (IOException ignore) {
      return new JsonObject();
    } finally {
      try {
        br.close();
      } catch (Exception ignore) {
      }
    }
    return new JsonObject(sb.toString());
  }

  // run two http servers in parallel
  @Override
  public void start() throws Exception {
    // read config file
    JsonObject globalConfig = config();
    if (globalConfig.isEmpty()) {
      globalConfig = getInternalConfig();
    }
    ServerConfigBuilder builder = new ServerConfigBuilder(globalConfig);
    Properties properties = builder.build();
    ServerConfigParser parser = builder.getParser();

    int workerInstanceCount = parser.getInt(ConfigObject.FLUORINE, "workerInstanceCount", 100);
    int webInstanceCount = parser.getInt(ConfigObject.FLUORINE, "webInstanceCount", 100);
    int workerPoolSize = Math.max(VertxOptions.DEFAULT_WORKER_POOL_SIZE, workerInstanceCount);
    ServerConfig.WORKER_POOL_SIZE = workerPoolSize;
    ServerConfig.WORKER_INSTANCE_COUNT = webInstanceCount;
    DeploymentOptions depOpt = new DeploymentOptions(
            new JsonObject().put("config", JsonObject.mapFrom(properties)))
        .setWorker(true)
        .setWorkerPoolSize(workerPoolSize)
        .setInstances(webInstanceCount)
        .setMaxWorkerExecuteTime(90L * 1000 * 1000000);
    System.out.println("SERVER CONFIG: \n" + "WORKER_POOL_SIZE: "
        + workerPoolSize + " \n" + "WORKER_INSTANCE_COUNT: "
        + webInstanceCount);

    // we should discuss if we want to do this. It seems with many verticles deployed this might not
    // be necessary.
    // disabled this for now, but we can enable this again on per verticle basis.
    // depOpt.setMultiThreaded(true);

    Map<String, String> verticles = new HashMap<>();
    // adding all required verticles
    builder.addVerticles(verticles);

    // create list of futures
    List<Future<String>> queue = new ArrayList<>();
    verticles.entrySet().parallelStream().forEach(entry -> {
      Promise<String> promise = Promise.promise();
      queue.add(promise.future());
      vertx.deployVerticle(entry.getKey(), depOpt, handler -> {
        if (handler.succeeded()) {
          vertx.sharedData().getLocalMap("verticle.addresses").put(entry.getValue(), "deployed");
          promise.complete("Deployment succeeded for " + entry.getKey());
        } else {
          promise.fail("Deployment failed for " + entry.getKey() + " : " + handler.cause());
        }
      });
    });

    TypedCompositeFuture.all(queue).onComplete(ar -> {
      // we are done with first set of verticles

      // later we should do the same with these.
      if (ar.succeeded()) {
        AWSXRayUnirest.init(properties.getxRayEnabled());

        vertx.deployVerticle(
            Periodic.class.getName(),
            new DeploymentOptions(new JsonObject().put("config", JsonObject.mapFrom(properties)))
                .setWorker(true));

        // we only one copy of this one
        vertx.deployVerticle(
            NotifyUser.class.getName(),
            new DeploymentOptions(new JsonObject().put("config", JsonObject.mapFrom(properties)))
                .setWorker(true),
            handler -> {
              if (handler.succeeded()) {
                vertx
                    .sharedData()
                    .getLocalMap("verticle.addresses")
                    .put(NotifyUser.address, "deployed");
              }
            });

        if (properties.getOnshapedev()) {
          // app is registered here: https://demo-c-dev-portal.dev.onshape.com/oauthApps
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONSHAPE_DEV.getLabel(), true));
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONSHAPE_STAGING.getLabel(), false));
          vertx.deployVerticle(Onshape.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(Onshape.addressDev, "deployed");
            }
          });
        }
        if (properties.getOnshapestaging()) {
          // app is registered here: https://staging-dev-portal.dev.onshape.com/oauthApps
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONSHAPE_DEV.getLabel(), false));
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONSHAPE_STAGING.getLabel(), true));
          vertx.deployVerticle(Onshape.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(Onshape.addressStaging, "deployed");
            }
          });
        }
        if (properties.getOnshape()) {
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONSHAPE_DEV.getLabel(), false));
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONSHAPE_STAGING.getLabel(), false));
          vertx.deployVerticle(Onshape.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(Onshape.addressProduction, "deployed");
            }
          });
        }
        if (properties.getHancom()) {
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.HANCOM_STAGING.getLabel(), false));
          vertx.deployVerticle(Hancom.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(Hancom.addressProd, "deployed");
            }
          });
        }
        if (properties.getHancomstg()) {
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.HANCOM_STAGING.getLabel(), true));
          vertx.deployVerticle(Hancom.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(Hancom.addressStg, "deployed");
            }
          });
        }
        if (properties.getOnedrive()) {
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONEDRIVEBUSINESS.getLabel(), true));
          vertx.deployVerticle(OneDrive.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(OneDrive.addressBusiness, "deployed");
            }
          });
          depOpt.setConfig(depOpt.getConfig().put(ConfigObject.ONEDRIVEBUSINESS.getLabel(), false));
          vertx.deployVerticle(OneDrive.class.getName(), depOpt, handler -> {
            if (handler.succeeded()) {
              vertx
                  .sharedData()
                  .getLocalMap("verticle.addresses")
                  .put(OneDrive.addressPersonal, "deployed");
            }
          });
        }
        String domain = null;
        try {
          if (!new URL(properties.getUrl()).getHost().equals("localhost")) {
            domain = InternetDomainName.from(new URL(properties.getUrl()).getHost())
                .topPrivateDomain()
                .toString();
          }
        } catch (Exception ex) {
          DynamoBusModBase.log.error(ex);
        }
        CustomProperties storageVerticleProps = builder.buildForStorageVerticle(domain);
        vertx.deployVerticle(
            StorageVerticle.class.getName(),
            new DeploymentOptions()
                .setConfig(JsonObject.mapFrom(storageVerticleProps))
                .setInstances(webInstanceCount));
        DynamoBusModBase.log.info("Done loading verticles.");

        XRayManager.initialize(properties.getxRayEnabled(), MetricsService.create(vertx));
        AWSKinesisClient.init(properties);

        // make sure we preload users list
        new Thread(UsersList::getNewUserList).start();
      } else {
        // we need to bail!
        DynamoBusModBase.log.error("Error loading verticles " + ar.result());
        throw new RuntimeException("FATAL ERROR: Cannot init verticles");
      }
    });
  }
}
