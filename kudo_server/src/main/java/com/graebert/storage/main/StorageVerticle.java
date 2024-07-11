package com.graebert.storage.main;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.blocklibrary.BlockLibraryManager;
import com.graebert.storage.comment.CommentVerticle;
import com.graebert.storage.config.CustomProperties;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.fonts.FontsVerticle;
import com.graebert.storage.gridfs.FileBuffer;
import com.graebert.storage.gridfs.FileFormats;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.GridFSModule;
import com.graebert.storage.gridfs.ObjectId;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.handler.AWSXRayHandler;
import com.graebert.storage.handler.EncodeReRouteHandler;
import com.graebert.storage.handler.RateLimitationHandler;
import com.graebert.storage.handler.RequestLogHandler;
import com.graebert.storage.handler.RequestMetadataHandler;
import com.graebert.storage.handler.ResponseTimeHandlerStatsd;
import com.graebert.storage.handler.Shutdown;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.GDrive;
import com.graebert.storage.integration.NextCloud;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.TrimbleConnect;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.MessagingManager;
import com.graebert.storage.resources.ResourceModule;
import com.graebert.storage.resources.ResourceOwnerType;
import com.graebert.storage.resources.integration.BaseResource;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.AuthProvider;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.stats.StatsVerticle;
import com.graebert.storage.stats.logs.file.FileActions;
import com.graebert.storage.stats.logs.file.FileLog;
import com.graebert.storage.storage.S3PresignedUploadRequests;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.XenonSessions;
import com.graebert.storage.storage.zipRequest.ExcludeReason;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.tmpl.TmplVerticle;
import com.graebert.storage.users.UsersVerticle;
import com.graebert.storage.util.CorsConfigOptions;
import com.graebert.storage.util.CustomRateLimiter;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.RequestUtils;
import com.graebert.storage.util.StreamHelper;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.vertx.BaseVerticle;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.vertx.HttpStatusCodes;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayDataHandler;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.APIKeyHandler;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.ResponseTimeHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.BadRequestException;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.ParameterProcessorException;
import io.vertx.ext.web.validation.RequestPredicateException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NonNls;

public class StorageVerticle extends BaseVerticle {
  private static final OperationGroup operationGroup = OperationGroup.STORAGE_VERTICLE;

  @NonNls
  private static final String OK = Field.OK.getName();

  @NonNls
  private static final String JSON = "json";

  @NonNls
  private static final String ERROR = Field.ERROR.getName();

  @NonNls
  private static final String GOOGLE = "google";

  private static final String emailRegExp = ".+@.+\\.[a-zA-Z]+";
  private static final String encodeRegExp = ".+[^\\w\\/\\-]+.*";
  private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

  private static final List<String> exposedHeaders =
      Arrays.asList(HttpHeaders.CONTENT_LENGTH.toString(), "Authenticate");
  private final String requestFailedPrefix = "Request Failed : ";
  private HttpServer server;

  private final ServerConfig config = new ServerConfig();

  private Authorization adminAuth;
  private String securityHeaderSchema, securityCookieSchema;

  private <T, K> void eb_request_with_metrics(
      Entity segment,
      RoutingContext routingContext,
      String address,
      T bufferOfJsonToSend,
      Handler<AsyncResult<Message<K>>> replyHandler) {
    try {
      JsonObject metrics = metricsService.getMetricsSnapshot(vertx.eventBus());

      if (bufferOfJsonToSend instanceof JsonObject) {
        ((JsonObject) bufferOfJsonToSend)
            .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext));
        if (metrics != null) {
          ((JsonObject) bufferOfJsonToSend)
              .put(
                  "eb.pending-local.atstart." + address,
                  metrics.getJsonObject("messages.pending-local").getInteger("count"))
              .put(
                  "eb.pending.atstart." + address,
                  metrics.getJsonObject("messages.pending").getInteger("count"))
              .put(
                  "eb.pending-remote.atstart." + address,
                  metrics.getJsonObject("messages.pending-remote").getInteger("count"));
        }
      }
    } catch (Exception exception) {
      DynamoBusModBase.log.error("metrics", exception);
    }

    eb_request(segment, address, bufferOfJsonToSend, replyHandler);
  }

  private <T> void eb_send_with_metrics(
      Entity segment, RoutingContext routingContext, String address, T bufferOfJsonToSend) {
    try {
      JsonObject metrics = metricsService.getMetricsSnapshot(vertx.eventBus());

      if (bufferOfJsonToSend instanceof JsonObject) {
        ((JsonObject) bufferOfJsonToSend)
            .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext));
        if (metrics != null) {
          ((JsonObject) bufferOfJsonToSend)
              .put(
                  "eb.pending-local.atstart." + address,
                  metrics.getJsonObject("messages.pending-local").getInteger("count"))
              .put(
                  "eb.pending.atstart." + address,
                  metrics.getJsonObject("messages.pending").getInteger("count"))
              .put(
                  "eb.pending-remote.atstart." + address,
                  metrics.getJsonObject("messages.pending-remote").getInteger("count"));
        }
      }
    } catch (Exception exception) {
      DynamoBusModBase.log.error("metrics", exception);
    }

    eb_send(segment, address, bufferOfJsonToSend);
  }

  public void start() throws Exception {
    super.start();
    CustomProperties customProperties = CustomProperties.fromJson(config());
    config.initCustom(customProperties);
    adminAuth = RoleBasedAuthorization.create("admin");
    securityHeaderSchema = config.getCustomProperties().getSecurityHeaderSchema();
    securityCookieSchema = config.getCustomProperties().getSecurityCookieSchema();
    server = vertx.createHttpServer(new HttpServerOptions());
    String openApiYaml = "documentation.yaml";
    try {
      RouterBuilder.create(vertx, openApiYaml)
          .onSuccess(routerBuilder -> {
            // {PLATFORM} handler
            routerBuilder.rootHandler(ResponseTimeHandler.create());

            // {SECURITY_POLICY} handler
            routerBuilder.rootHandler(CorsHandler.create()
                .allowedMethods(CorsConfigOptions.getAllowedMethods())
                .allowedHeaders(CorsConfigOptions.getAllowedHeaders())
                .exposedHeaders(new HashSet<>(exposedHeaders)));

            // {BODY} handler
            routerBuilder.rootHandler(BodyHandler.create()
                .setUploadsDirectory(System.getProperty("java.io.tmpdir"))
                .setBodyLimit((long) config.getCustomProperties().getMaxUploadSize() * 1024 * 1024)
                .setDeleteUploadedFilesOnEnd(true).setHandleFileUploads(true));

            // security handler
            addSecurityHandlers(routerBuilder);
            // {USER} Handlers ------ start ------
            if (XRayManager.isXrayEnabled()) {
              routerBuilder.rootHandler(new AWSXRayHandler());
            }
            routerBuilder.rootHandler(new Shutdown());
            routerBuilder.rootHandler(new RequestLogHandler(emptyString));
            routerBuilder.rootHandler(new ResponseTimeHandlerStatsd());

            // to handle encoded metadata(headers/form attributes) in the request
            routerBuilder.rootHandler(new RequestMetadataHandler());
            // ---------------------- end ------

            // to handle rate limit for all general API requests
            if (config.getCustomProperties().getRateLimiterEnabled()) {
              RateLimitationHandler rootRateLimiter = new RateLimitationHandler(
                  Duration.ofSeconds(1), config.getCustomProperties().getRateLimit());
              routerBuilder.rootHandler(event -> {
                if (CustomRateLimiter.isCustomRateLimiter(
                    event.request().path(), event.request().method())) {
                  event.next();
                  return;
                }
                rootRateLimiter.handle(event);
              });
            }

            createRoutes(routerBuilder);
            Router router = routerBuilder.createRouter();

            if (config.getCustomProperties().getRateLimiterEnabled()) {
              // Custom Rate Limiters ---- start ----

              RateLimitationHandler presignedRateLimiter =
                  new RateLimitationHandler(Duration.ofMinutes(1), 15);
              // to handle rate limit of presigned upload request
              router.route(HttpMethod.GET, "/files/signedurl/generate").handler(event -> {
                presignedRateLimiter.setRateLimiterName(event, "PresignedUpload");
                presignedRateLimiter.handle(event);
              });

              // Custom Rate Limiters ---- end ----
            }

            // to reroute any URL with path parameters (containing special characters)
            router.routeWithRegex(encodeRegExp).last().handler(event -> {
              XRayManager.endSegment(XRayManager.getCurrentSegment().orElse(null));
              new EncodeReRouteHandler().handle(event);
            });

            // Static Handler
            router
                .route("/static/*")
                .handler(StaticHandler.create(FileSystemAccess.ROOT, "/usr/share/nginx/www"));

            mountSubRouter("/v1", openApiYaml, router);

            if (config.getCustomProperties().getMountApi()) {
              mountSubRouter("/api", openApiYaml, router);
              mountSubRouter("/router/v1", openApiYaml, router);
            }
            // Error Handler
            errorHandling(router);

            server.requestHandler(router);
            server.listen(
                config.getCustomProperties().getServerPort(),
                config.getCustomProperties().getHost());
          })
          .onFailure(cause -> log.error("Router build failed : " + cause));
    } catch (Exception e) {
      log.error("Exception occurred in router builder : " + e);
    }
    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-storageverticle");
  }

  private void mountSubRouter(String mountPoint, String openapiURL, Router router) {
    RouterBuilder.create(vertx, openapiURL)
        .onSuccess(routerBuilder -> {
          addSecurityHandlers(routerBuilder);
          createRoutes(routerBuilder);
          router.route("/demoemail").handler(this::doSendEmail);
          router.route(mountPoint + "/*").subRouter(routerBuilder.createRouter());
        })
        .onFailure(cause -> log.error("Error in mounting sub route - " + mountPoint + ":" + cause));
  }

  private void addSecurityHandlers(RouterBuilder routerBuilder) {
    routerBuilder
        .securityHandler(securityHeaderSchema)
        .bindBlocking(config ->
            APIKeyHandler.create(new AuthProvider()).header(config.getString(Field.NAME.getName())))
        .securityHandler(securityCookieSchema)
        .bindBlocking(config -> APIKeyHandler.create(new AuthProvider())
            .cookie(config.getString(Field.NAME.getName())));
  }

  private void createRoutes(RouterBuilder routerBuilder) {
    routerBuilder
        .operations()
        .forEach(operation -> operation.handler(new XRayDataHandler(operation.getOperationId())));

    routerBuilder.operation("emailShare").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("emailShare").getOperationModel().getMap());
      doGetMentionUsers(event);
    });
    routerBuilder.operation("getUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getUser").getOperationModel().getMap());
      doGetUsersInfo(event);
    });
    routerBuilder.operation("updateUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateUser").getOperationModel().getMap());
      doUpdateProfile(event);
    });
    routerBuilder.operation("addUser").handler(this::doCreateUser);
    routerBuilder.operation("deleteUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteUser").getOperationModel().getMap());
      doDeleteUser(event);
    });
    routerBuilder.operation("addForeignUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addForeignUser").getOperationModel().getMap());
      doVerify(event);
    });
    routerBuilder.operation("deleteForeignUser").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteForeignUser").getOperationModel().getMap());
      doDeleteForeign(event);
    });
    routerBuilder.operation("ssoLogin").handler(this::doSSOLogin);
    routerBuilder.operation("addPortalUser").handler(this::doCreatePortalUser);
    routerBuilder.operation("checkPortalUserExist").handler(this::doCheckPortalExists);
    routerBuilder.operation("usersConfirm").handler(this::doNotifyAdmins);
    routerBuilder.operation("findUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("findUser").getOperationModel().getMap());
      doFindUser(event);
    });
    routerBuilder.operation("resetUserRequest").handler(this::doRequestResetPwd);
    routerBuilder.operation("resetUserPwd").handler(this::doResetPwd);
    routerBuilder.operation("tryResetUser").handler(this::doTryReset);
    routerBuilder.operation("changeEmail").handler(this::doChangeEmail);
    routerBuilder.operation("authenticate").handler(this::doAuthenticate);
    routerBuilder.operation("logout").handler(this::doLogout);
    routerBuilder.operation("checkAuth").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("checkAuth").getOperationModel().getMap());
      doCheckAuth(event);
    });
    routerBuilder.operation("adminGetUsers").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetUsers").getOperationModel().getMap());
      doGetUsersInfo(event);
    });
    routerBuilder.operation("adminGetSpecificUserId").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetSpecificUserId").getOperationModel().getMap());
      doGetUsersInfo(event);
    });
    routerBuilder.operation("adminGetUserAccounts").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetUserAccounts").getOperationModel().getMap());
      doGetFullExternalAccountsInfo(event);
    });
    routerBuilder.operation("adminUpdateUsers").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminUpdateUsers").getOperationModel().getMap());
      doUpdateUsers(event);
    });
    routerBuilder.operation("adminUpdateUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminUpdateUser").getOperationModel().getMap());
      doUpdateUser(event);
    });
    routerBuilder.operation("adminDeleteUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminDeleteUser").getOperationModel().getMap());
      doDeleteUser(event);
    });
    routerBuilder.operation("getAdminStorages").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getAdminStorages").getOperationModel().getMap());
      doGetAvailableStorages(event);
    });
    routerBuilder.operation("updateUserSkelton").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateUserSkelton").getOperationModel().getMap());
      doUpdateSkeleton(event);
    });
    routerBuilder.operation("createUserSkelton").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("createUserSkelton").getOperationModel().getMap());
      doCreateSkeleton(event);
    });
    routerBuilder.operation("adminCreateUser").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminCreateUser").getOperationModel().getMap());
      doAdminCreateUser(event);
    });
    routerBuilder.operation("adminGetFiles").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetFiles").getOperationModel().getMap());
      doGetFiles(event);
    });
    routerBuilder.operation("adminGetFolders").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetFolders").getOperationModel().getMap());
      doGetFiles(event);
    });
    routerBuilder.operation("adminAddTemplate").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminAddTemplate").getOperationModel().getMap());
      doUploadTmpl(event);
    });
    routerBuilder.operation("adminUpdateTemplate").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminUpdateTemplate").getOperationModel().getMap());
      doUpdateTmpl(event);
    });
    routerBuilder.operation("adminDeleteTemplate").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminDeleteTemplate").getOperationModel().getMap());
      doDeleteTmpl(event);
    });
    routerBuilder.operation("adminDeleteSession").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminDeleteSession").getOperationModel().getMap());
      doKillSession(event);
    });
    routerBuilder.operation("adminGetFilesLog").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetFilesLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetSessionsLog").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetSessionsLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetUsersLog").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetUsersLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetStoragesLog").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetStoragesLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetSharesLog").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetSharesLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetSubscriptionsLog").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("adminGetSubscriptionsLog")
              .getOperationModel()
              .getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetPerformanceLog").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetPerformanceLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetLinksLog").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetLinksLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetCachedLinksLog").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("adminGetCachedLinksLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("adminGetJobLog").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("adminGetJobLog").getOperationModel().getMap());
      doHandleLogs(event);
    });
    routerBuilder.operation("savePerformanceStats").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("savePerformanceStats").getOperationModel().getMap());
      doSavePerformanceStats(event);
    });
    routerBuilder.operation("cloneCustomTemplate").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("cloneCustomTemplate").getOperationModel().getMap());
      doCloneTmpl(event);
    });
    routerBuilder.operation("getTemplates").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getTemplates").getOperationModel().getMap());
      doGetTemplates(event);
    });
    routerBuilder.operation("addTemplate").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addTemplate").getOperationModel().getMap());
      doUploadTmpl(event);
    });
    routerBuilder.operation("deleteTemplates").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteTemplates").getOperationModel().getMap());
      doDeleteTmpl(event);
    });
    routerBuilder.operation("getTemplate").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getTemplate").getOperationModel().getMap());
      doGetTmpl(event);
    });
    routerBuilder.operation("updateTemplate").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateTemplate").getOperationModel().getMap());
      doUpdateTmpl(event);
    });
    routerBuilder.operation("deleteTemplate").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteTemplate").getOperationModel().getMap());
      doDeleteTmpl(event);
    });
    routerBuilder.operation("cloneTemplate").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("cloneTemplate").getOperationModel().getMap());
      doCloneTmpl(event);
    });
    routerBuilder.operation("getUserTemplates").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getUserTemplates").getOperationModel().getMap());
      doGetUserTmpls(event);
    });
    routerBuilder.operation("getTrash").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getTrash").getOperationModel().getMap());
      doGetFiles(event);
    });
    routerBuilder.operation("updateTrash").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateTrash").getOperationModel().getMap());
      doTrashBatch(event);
    });
    routerBuilder.operation("eraseAllTrash").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("eraseAllTrash").getOperationModel().getMap());
      doEraseAll(event);
    });
    routerBuilder.operation("getTrashFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getTrashFolder").getOperationModel().getMap());
      doGetFiles(event);
    });
    routerBuilder.operation("sendMessage").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("sendMessage").getOperationModel().getMap());
      doSendMsg(event);
    });
    routerBuilder.operation("readMessage").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("readMessage").getOperationModel().getMap());
      doReadMsg(event);
    });
    routerBuilder.operation("deleteMessage").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteMessage").getOperationModel().getMap());
      doDeleteMsg(event);
    });
    routerBuilder.operation("getFonts").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFonts").getOperationModel().getMap());
      doGetUsersFonts(event);
    });
    routerBuilder.operation("getCompanyFonts").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getCompanyFonts").getOperationModel().getMap());
      doGetUsersFonts(event);
    });
    routerBuilder.operation("addCompanyFont").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addCompanyFont").getOperationModel().getMap());
      doUploadUsersFont(event);
    });
    routerBuilder.operation("addUserFont").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addUserFont").getOperationModel().getMap());
      doUploadUsersFont(event);
    });
    routerBuilder.operation("getUserFonts").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getUserFonts").getOperationModel().getMap());
      doGetUsersFonts(event);
    });
    routerBuilder.operation("getFont").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFont").getOperationModel().getMap());
      doGetUsersFont(event);
    });
    routerBuilder.operation("deleteFont").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteFont").getOperationModel().getMap());
      doDeleteUsersFont(event);
    });
    routerBuilder.operation("sendFeedback").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("sendFeedback").getOperationModel().getMap());
      doSendFeedback(event);
    });
    routerBuilder.operation("getCompany").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getCompany").getOperationModel().getMap());
      doGetCompany(event);
    });
    routerBuilder.operation("updateCompany").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateCompany").getOperationModel().getMap());
      doUpdateCompany(event);
    });
    routerBuilder.operation("addStorage").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addStorage").getOperationModel().getMap());
      doAddStorage(event);
    });
    routerBuilder.operation("getFiles").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFiles").getOperationModel().getMap());
      doGetFiles(event);
    });
    routerBuilder.operation("uploadFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("uploadFile").getOperationModel().getMap());
      doUploadFile(event);
    });
    routerBuilder.operation("checkUpload").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("checkUpload").getOperationModel().getMap());
      doCheckUpload(event);
    });
    routerBuilder.operation("cancelUpload").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("cancelUpload").getOperationModel().getMap());
      doCancelUpload(event);
    });
    routerBuilder.operation("connectStorage").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("connectStorage").getOperationModel().getMap());
      doStoreConnection(event);
    });
    routerBuilder.operation("getRecentFiles").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getRecentFiles").getOperationModel().getMap());
      doGetRecentFiles(event);
    });
    routerBuilder.operation("deleteRecentFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteRecentFile").getOperationModel().getMap());
      doDeleteRecentFile(event);
    });
    routerBuilder.operation("restoreRecentFile").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("restoreRecentFile").getOperationModel().getMap());
      doRestoreRecentFile(event);
    });
    routerBuilder.operation("validateRecentFiles").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("validateRecentFiles").getOperationModel().getMap());
      doValidateRecentFiles(event);
    });
    routerBuilder.operation("validateRecentFile").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("validateRecentFile").getOperationModel().getMap());
      doValidateSingleRecentFile(event);
    });
    routerBuilder.operation("searchFiles").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("searchFiles").getOperationModel().getMap());
      doSearch(event);
    });
    routerBuilder.operation("getNotificationsList").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getNotificationsList").getOperationModel().getMap());
      doGetNotifications(event);
    });
    routerBuilder.operation("markNotifications").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("markNotifications").getOperationModel().getMap());
      doMarkNotifications(event);
    });
    routerBuilder.operation("updateFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateFile").getOperationModel().getMap());
      doUpdateFile(event);
    });
    routerBuilder.operation("deleteFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteFile").getOperationModel().getMap());
      doDeleteFile(event);
    });
    routerBuilder.operation("cloneFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("cloneFile").getOperationModel().getMap());
      doCloneFile(event);
    });
    routerBuilder.operation("getFileData").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFileData").getOperationModel().getMap());
      doGetFileData(event);
    });
    routerBuilder.operation("getFileInfo").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFileInfo").getOperationModel().getMap());
      doGetObjectInfo(event);
    });
    routerBuilder.operation("getDeletedFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getDeletedFile").getOperationModel().getMap());
      doGetTrashed(event);
    });
    routerBuilder.operation("getFileThumbnail").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFileThumbnail").getOperationModel().getMap());
      doGetThumbnail(event);
    });
    routerBuilder.operation("uploadFilePreview").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("uploadFilePreview").getOperationModel().getMap());
      doUploadPreview(event);
    });
    routerBuilder.operation("getSharedLink").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getSharedLink").getOperationModel().getMap());
      doGetSharedLink(event);
    });
    routerBuilder.operation("getFileLinks").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFileLinks").getOperationModel().getMap());
      doGetFileLinks(event);
    });
    routerBuilder.operation("deleteFileVersionDownloadLink").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("deleteFileVersionDownloadLink")
              .getOperationModel()
              .getMap());
      doDeleteFileVersionDownloadLink(event);
    });
    routerBuilder.operation("deleteFileVersionViewLink").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("deleteFileVersionViewLink")
              .getOperationModel()
              .getMap());
      doDeleteFileVersionViewLink(event);
    });
    routerBuilder.operation("generateFileVersionDownloadLink").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("generateFileVersionDownloadLink")
              .getOperationModel()
              .getMap());
      doGetFileVersionDownloadLink(event);
    });
    routerBuilder.operation("generateFileVersionViewLink").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("generateFileVersionViewLink")
              .getOperationModel()
              .getMap());
      doGetFileVersionViewLink(event);
    });
    routerBuilder.operation("updateSharedLink").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateSharedLink").getOperationModel().getMap());
      doUpdateSharedLink(event);
    });
    routerBuilder.operation("sendSharedLink").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("sendSharedLink").getOperationModel().getMap());
      doSendSharedLink(event);
    });
    routerBuilder.operation("removeSharedLink").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("removeSharedLink").getOperationModel().getMap());
      doRemoveSharedLink(event);
    });
    routerBuilder.operation("requestFileAccess").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("requestFileAccess").getOperationModel().getMap());
      doRequestFile(event);
    });
    routerBuilder.operation("getXenonSession").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getXenonSession").getOperationModel().getMap());
      doGetXSessions(event);
    });
    routerBuilder.operation("updateXenonSession").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateXenonSession").getOperationModel().getMap());
      doUpdateXSession(event);
    });
    routerBuilder.operation("saveXenonSession").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("saveXenonSession").getOperationModel().getMap());
      doSaveXSession(event);
    });
    routerBuilder.operation("removeXenonSession").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("removeXenonSession").getOperationModel().getMap());
      doRemoveXSession(event);
    });
    routerBuilder.operation("requestXenonSession").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("requestXenonSession").getOperationModel().getMap());
      doRequestXSession(event);
    });
    routerBuilder.operation("denyXenonSession").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("denyXenonSession").getOperationModel().getMap());
      doDenyXsession(event);
    });
    routerBuilder.operation("trashFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("trashFile").getOperationModel().getMap());
      doTrashFile(event);
    });
    routerBuilder.operation("unTrashFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("unTrashFile").getOperationModel().getMap());
      doUntrashFile(event);
    });
    routerBuilder.operation("downloadFile").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("downloadFile").getOperationModel().getMap());
      doDownloadFile(event);
    });
    routerBuilder.operation("getFileXref").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFileXref").getOperationModel().getMap());
      doGetXRef(event);
    });
    routerBuilder.operation("checkFileXrefPath").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("checkFileXrefPath").getOperationModel().getMap());
      doCheckXRefPath(event);
    });
    routerBuilder.operation("getFileVersions").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFileVersions").getOperationModel().getMap());
      doGetVersions(event);
    });
    routerBuilder.operation("uploadFileVersion").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("uploadFileVersion").getOperationModel().getMap());
      doUploadVersion(event);
    });
    routerBuilder.operation("getLatestFileVersion").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getLatestFileVersion").getOperationModel().getMap());
      getLatestVersionId(event);
    });
    routerBuilder.operation("getFileVersionInfo").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getFileVersionInfo").getOperationModel().getMap());
      doGetObjectInfo(event);
    });
    routerBuilder.operation("getFileVersionData").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getFileVersionData").getOperationModel().getMap());
      doGetVersionData(event);
    });
    routerBuilder.operation("deleteFileVersion").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteFileVersion").getOperationModel().getMap());
      doDeleteData(event);
    });
    routerBuilder.operation("promoteFileVersion").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("promoteFileVersion").getOperationModel().getMap());
      doPromoteVersion(event);
    });
    routerBuilder.operation("markVersionAsPrinted").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("markVersionAsPrinted").getOperationModel().getMap());
      doMarkVersionAsPrinted(event);
    });
    routerBuilder.operation("getAnnotations").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getAnnotations").getOperationModel().getMap());
      doGetAllAnnotations(event);
    });
    routerBuilder.operation("getCommentThreads").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getCommentThreads").getOperationModel().getMap());
      doGetAnnotations(event);
    });
    routerBuilder.operation("getCommentThread").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getCommentThread").getOperationModel().getMap());
      doGetAnnotation(event);
    });
    routerBuilder.operation("addCommentThread").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addCommentThread").getOperationModel().getMap());
      doAddAnnotation(event);
    });
    routerBuilder.operation("updateCommentThread").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateCommentThread").getOperationModel().getMap());
      doUpdateAnnotation(event);
    });
    routerBuilder.operation("deleteCommentThread").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteCommentThread").getOperationModel().getMap());
      doDeleteAnnotation(event);
    });
    routerBuilder.operation("addComment").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addComment").getOperationModel().getMap());
      doAddComment(event);
    });
    routerBuilder.operation("updateComment").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateComment").getOperationModel().getMap());
      doUpdateComment(event);
    });
    routerBuilder.operation("deleteComment").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteComment").getOperationModel().getMap());
      doDeleteComment(event);
    });
    routerBuilder.operation("getMarkups").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getMarkups").getOperationModel().getMap());
      doGetAnnotations(event);
    });
    routerBuilder.operation("addMarkup").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addMarkup").getOperationModel().getMap());
      doAddAnnotation(event);
    });
    routerBuilder.operation("getMarkup").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getMarkup").getOperationModel().getMap());
      doGetAnnotation(event);
    });
    routerBuilder.operation("updateMarkup").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateMarkup").getOperationModel().getMap());
      doUpdateAnnotation(event);
    });
    routerBuilder.operation("deleteMarkup").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteMarkup").getOperationModel().getMap());
      doDeleteAnnotation(event);
    });
    routerBuilder.operation("addMarkupComment").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addMarkupComment").getOperationModel().getMap());
      doAddComment(event);
    });
    routerBuilder.operation("updateMarkupComment").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateMarkupComment").getOperationModel().getMap());
      doUpdateComment(event);
    });
    routerBuilder.operation("deleteMarkupComment").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteMarkupComment").getOperationModel().getMap());
      doDeleteComment(event);
    });
    routerBuilder.operation("getAttachments").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getAttachments").getOperationModel().getMap());
      doGetAttachments(event);
    });
    routerBuilder.operation("addAttachment").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addAttachment").getOperationModel().getMap());
      doAddAttachment(event);
    });
    routerBuilder.operation("getAttachment").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getAttachment").getOperationModel().getMap());
      doGetAttachment(event);
    });
    routerBuilder.operation("getOriginalAttachment").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getOriginalAttachment").getOperationModel().getMap());
      doGetAttachment(event);
    });
    routerBuilder.operation("getAttachmentDescription").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("getAttachmentDescription")
              .getOperationModel()
              .getMap());
      doGetAttachmentDescription(event);
    });
    routerBuilder.operation("getSubscription").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getSubscription").getOperationModel().getMap());
      doGetSubscription(event);
    });
    routerBuilder.operation("addSubscription").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("addSubscription").getOperationModel().getMap());
      doAddSubscription(event);
    });
    routerBuilder.operation("deleteSubscription").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteSubscription").getOperationModel().getMap());
      doDeleteSubscription(event);
    });
    routerBuilder.operation("getNotification").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getNotification").getOperationModel().getMap());
      doGetFileNotifications(event);
    });
    routerBuilder.operation("createFileShortcut").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("createFileShortcut").getOperationModel().getMap());
      doCreateFileShortcut(event);
    });
    routerBuilder.operation("createFolderShortcut").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("createFolderShortcut").getOperationModel().getMap());
      doCreateFolderShortcut(event);
    });
    routerBuilder.operation("markNotification").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("markNotification").getOperationModel().getMap());
      doMarkFileNotifications(event);
    });
    routerBuilder.operation("getFolderXref").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFolderXref").getOperationModel().getMap());
      doGetXRef(event);
    });
    routerBuilder.operation("checkFolderXrefPath").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("checkFolderXrefPath").getOperationModel().getMap());
      doCheckXRefPath(event);
    });
    routerBuilder.operation("createFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("createFolder").getOperationModel().getMap());
      doCreateFolder(event);
    });
    routerBuilder.operation("getFolders").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFolders").getOperationModel().getMap());
      doGetFiles(event);
    });
    routerBuilder.operation("updateFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateFolder").getOperationModel().getMap());
      doUpdateFolder(event);
    });
    routerBuilder.operation("updateFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateFolder").getOperationModel().getMap());
      doUpdateFolder(event);
    });
    routerBuilder.operation("deleteFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteFolder").getOperationModel().getMap());
      doDeleteFolder(event);
    });
    routerBuilder.operation("getFolderOwners").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFolderOwners").getOperationModel().getMap());
      doGetOwners(event);
    });
    routerBuilder.operation("cloneFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("cloneFolder").getOperationModel().getMap());
      doCloneFolder(event);
    });
    routerBuilder.operation("downloadFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("downloadFolder").getOperationModel().getMap());
      doDownloadFolder(event);
    });
    routerBuilder.operation("downloadMultiple").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("downloadMultiple").getOperationModel().getMap());
      doDownloadMultiple(event);
    });
    routerBuilder.operation("getFolderInfo").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFolderInfo").getOperationModel().getMap());
      doGetObjectInfo(event);
    });
    routerBuilder.operation("getFolderPath").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFolderPath").getOperationModel().getMap());
      doGetFolderPath(event);
    });
    routerBuilder.operation("getFoldersPath").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getFoldersPath").getOperationModel().getMap());
      doGetFoldersPath(event);
    });
    routerBuilder.operation("trashFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("trashFolder").getOperationModel().getMap());
      doTrashFolder(event);
    });
    routerBuilder.operation("unTrashFolder").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("unTrashFolder").getOperationModel().getMap());
      doUntrashFolder(event);
    });
    routerBuilder.operation("getFolderMetadata").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getFolderMetadata").getOperationModel().getMap());
      doGetMetadata(event);
    });
    routerBuilder.operation("updateFolderMetadata").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateFolderMetadata").getOperationModel().getMap());
      doUpdateMetadata(event);
    });
    routerBuilder.operation("deleteFolderMetadata").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteFolderMetadata").getOperationModel().getMap());
      doDeleteMetadata(event);
    });
    routerBuilder.operation("saveSamlResponse").handler(this::doSaveSamlResponse);
    routerBuilder.operation("getRevision").handler(this::doGetRevision);
    routerBuilder.operation("getConfigProperties").handler(this::doGetConfigurationProperties);
    routerBuilder.operation("getMetrics").handler(this::doGetMetrics);
    routerBuilder.operation("getIntegrations").handler(this::doGetIntegrations);
    routerBuilder.operation("getIntegrationAccounts").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getIntegrationAccounts").getOperationModel().getMap());
      doGetIntegrationAccounts(event);
    });
    routerBuilder.operation("switchIntegrationAccount").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("switchIntegrationAccount")
              .getOperationModel()
              .getMap());
      doSwitchIntegrationAccount(event);
    });
    routerBuilder.operation("getAccountFileSessions").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getAccountFileSessions").getOperationModel().getMap());
      doGetAccountFileSessions(event);
    });
    routerBuilder.operation("deleteIntegrationAccount").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("deleteIntegrationAccount")
              .getOperationModel()
              .getMap());
      doDeleteIntegrationAccount(event);
    });
    routerBuilder.operation("eraseBatch").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("eraseBatch").getOperationModel().getMap());
      doEraseBatch(event);
    });
    routerBuilder.operation("restoreBatch").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("restoreBatch").getOperationModel().getMap());
      doRestoreBatch(event);
    });
    routerBuilder.operation("getIntegrationSettings").handler(this::doGetIntegrationSettings);
    routerBuilder.operation("getRegions").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getRegions").getOperationModel().getMap());
      doGetRegions(event);
    });
    routerBuilder.operation("usersToMention").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("usersToMention").getOperationModel().getMap());
      doGetMentionUsers(event);
    });
    routerBuilder.operation("compareFiles").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("compareFiles").getOperationModel().getMap());
      doCompareDrawings(event);
    });
    routerBuilder.operation("changeRegion").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("changeRegion").getOperationModel().getMap());
      doChangeRegion(event);
    });
    routerBuilder.operation("flushLogs").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("flushLogs").getOperationModel().getMap());
      doFlushLogs(event);
    });
    routerBuilder.operation("getMemcacheData").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getMemcacheData").getOperationModel().getMap());
      doAdminGetMemcached(event);
    });
    routerBuilder.operation("deleteMemcacheData").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteMemcacheData").getOperationModel().getMap());
      doAdminDeleteMemcached(event);
    });
    routerBuilder.operation("updateThumbnailGeneration").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("updateThumbnailGeneration")
              .getOperationModel()
              .getMap());
      doUpdateThumbnailGeneration(event);
    });
    routerBuilder.operation("doCognitoLogin").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("doCognitoLogin").getOperationModel().getMap());
      doCognitoLogin(event);
    });
    routerBuilder.operation("getCognitoLogin").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getCognitoLogin").getOperationModel().getMap());
      doCognitoLogin(event);
    });
    routerBuilder.operation("graebertLogin").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("graebertLogin").getOperationModel().getMap());
      doExtendPermission(event);
    });
    routerBuilder.operation("fileCheckIn").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("fileCheckIn").getOperationModel().getMap());
      doFileCheckin(event);
    });
    routerBuilder.operation("getBlockLibraries").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getBlockLibraries").getOperationModel().getMap());
      doGetBlockLibraries(event);
    });
    routerBuilder.operation("createBlockLibrary").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("createBlockLibrary").getOperationModel().getMap());
      doCreateBlockLibrary(event);
    });
    routerBuilder.operation("updateBlockLibrary").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateBlockLibrary").getOperationModel().getMap());
      doUpdateBlockLibrary(event);
    });
    routerBuilder.operation("getBlockLibraryInfo").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getBlockLibraryInfo").getOperationModel().getMap());
      doGetBlockLibraryInfo(event);
    });
    routerBuilder.operation("deleteBlockLibrary").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteBlockLibrary").getOperationModel().getMap());
      doDeleteBlockLibrary(event);
    });
    routerBuilder.operation("deleteBlockLibraries").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteBlockLibraries").getOperationModel().getMap());
      doDeleteBlockLibraries(event);
    });
    routerBuilder.operation("uploadBlock").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("uploadBlock").getOperationModel().getMap());
      doUploadBlock(event);
    });
    routerBuilder.operation("updateBlock").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("updateBlock").getOperationModel().getMap());
      doUpdateBlock(event);
    });
    routerBuilder.operation("deleteBlocks").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteBlocks").getOperationModel().getMap());
      doDeleteBlocks(event);
    });
    routerBuilder.operation("getBlocks").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getBlocks").getOperationModel().getMap());
      doGetBlocks(event);
    });
    routerBuilder.operation("deleteBlock").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("deleteBlock").getOperationModel().getMap());
      doDeleteBlock(event);
    });
    routerBuilder.operation("getBlockInfo").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getBlockInfo").getOperationModel().getMap());
      doGetBlockInfo(event);
    });
    routerBuilder.operation("getBlockContent").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("getBlockContent").getOperationModel().getMap());
      doGetBlockContent(event);
    });
    routerBuilder.operation("searchBlockLibrary").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("searchBlockLibrary").getOperationModel().getMap());
      doSearchBlockLibrary(event);
    });
    routerBuilder.operation("shareBlockLibrary").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("shareBlockLibrary").getOperationModel().getMap());
      doShareBlockLibrary(event);
    });
    routerBuilder.operation("unShareBlockLibrary").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("unShareBlockLibrary").getOperationModel().getMap());
      doUnShareBlockLibrary(event);
    });
    routerBuilder.operation("shareBlock").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("shareBlock").getOperationModel().getMap());
      doShareBlock(event);
    });
    routerBuilder.operation("unShareBlock").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("unShareBlock").getOperationModel().getMap());
      doUnShareBlock(event);
    });
    routerBuilder.operation("getUserCapabilities").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getUserCapabilities").getOperationModel().getMap());
      doGetUserCapabilities(event);
    });
    routerBuilder.operation("stopPoll").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("stopPoll").getOperationModel().getMap());
      doStopPoll(event);
    });

    routerBuilder.operation("getResourceFolderContent").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("getResourceFolderContent")
              .getOperationModel()
              .getMap());
      doGetResourceFolderContent(event);
    });

    routerBuilder.operation("createResourceObject").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("createResourceObject").getOperationModel().getMap());
      doCreateResourceObject(event);
    });

    routerBuilder.operation("updateResourceObject").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("updateResourceObject").getOperationModel().getMap());
      doUpdateResourceObject(event);
    });

    routerBuilder.operation("deleteResourceObjects").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("deleteResourceObjects").getOperationModel().getMap());
      doDeleteResourceObjects(event);
    });

    routerBuilder.operation("getResourceObjectInfo").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getResourceObjectInfo").getOperationModel().getMap());
      doGetResourceObjectInfo(event);
    });

    routerBuilder.operation("getResourceFolderPath").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getResourceFolderPath").getOperationModel().getMap());
      doGetResourceFolderPath(event);
    });

    routerBuilder.operation("downloadResourceObject").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("downloadResourceObject").getOperationModel().getMap());
      doDownloadResourceObject(event);
    });

    routerBuilder.operation("updateAdminStorageAccess").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("updateAdminStorageAccess")
              .getOperationModel()
              .getMap());
      doUpdateAdminStorageAccess(event);
    });

    routerBuilder.operation("getAdminDisabledStorages").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder
              .operation("getAdminDisabledStorages")
              .getOperationModel()
              .getMap());
      doGetAdminDisabledStorages(event);
    });

    routerBuilder.operation("checkFileSave").handler(event -> {
      setAdditionalParams(
          event, routerBuilder.operation("checkFileSave").getOperationModel().getMap());
      doCheckFileSave(event);
    });

    routerBuilder.operation("requestMultipleUpload").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("requestMultipleUpload").getOperationModel().getMap());
      doRequestMultipleUpload(event);
    });

    routerBuilder.operation("getS3PreSignedUploadURL").handler(event -> {
      setAdditionalParams(
          event,
          routerBuilder.operation("getS3PreSignedUploadURL").getOperationModel().getMap());
      doGetS3PreSignedUploadURL(event);
    });

    routerBuilder.operation("getRolesAndPermissions").handler(this::doGetRolesAndPermissions);
    routerBuilder.operation("getEmailImage").handler(this::doGetEmailImage);
    routerBuilder.operation("getFileRedirect").handler(this::doRedirectToFile);
    routerBuilder.operation("generateToken").handler(this::doGenerateToken);
    routerBuilder.operation("getNonce").handler(this::doGetNonce);
    routerBuilder.operation("getLongNonce").handler(this::doGetLongNonce);
    routerBuilder.operation("healthCheckUp").handler(this::doHealthCheckup);
  }

  private void setAdditionalParams(
      RoutingContext routingContext, Map<String, Object> operationData) {
    operationData.keySet().stream()
        .filter(key -> key.startsWith("x-"))
        .forEach(key -> routingContext.data().put(key.substring(2), operationData.get(key)));
  }

  // uncomment to debug
  //    private void doCheckSubscription(RoutingContext routingContext) {
  //        Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
  //        JsonObject body = new JsonObject();
  //        try {
  //            body = Utils.getBodyAsJson(routingContext);
  //        } catch (Exception ignore) {
  //        }
  //        body.mergeIn(GridFSModule.parseItemId(body.getString(Field.FILE_ID.getName()), Field
  //        .FILE_ID.getName()));
  //
  //        ebRequestWithMetrics()(optionalSegment.orElse(null), routingContext,
  //        NotificationEvents.address + "
  //        .getSubscriptionsList", body, (Handler<AsyncResult<Message<JsonObject>>>) message2 -> {
  //            simpleResponse(routingContext, message2, Utils.getLocalizedString(routingContext
  //            .request().headers().get(Field.LOCALE.getName()), "CouldNotGetNotifications"));
  //        });
  //
  //    }

  private void errorHandling(Router router) {
    router.errorHandler(HttpStatus.SC_BAD_REQUEST, routingContext -> {
      String finalMessage = requestFailedPrefix;
      String message = (routingContext.failure() != null)
          ? routingContext.failure().getMessage()
          : HttpResponseStatus.BAD_REQUEST.reasonPhrase();
      if (routingContext.failure() instanceof BadRequestException) {
        if (routingContext.failure() instanceof RequestPredicateException) {
          if (Utils.isStringNotNullOrEmpty(message)
              && message.contains("File with content type")
              && routingContext.request().headers().contains(Field.LOCALE.getName())) {
            message = Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "InvalidTypeOfFile");
          }
          if (Utils.isStringNotNullOrEmpty(message) && message.contains("Body required")) {
            message =
                Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "NoRequestBody");
          }
        } else if (routingContext.failure() instanceof ParameterProcessorException
            || routingContext.failure() instanceof BodyProcessorException) {
          if (Utils.isStringNotNullOrEmpty(message) && message.contains("]")) {
            int first = message.indexOf("]");
            message = message.substring(first + 2).toLowerCase();
            if (Utils.isStringNotNullOrEmpty(message) && message.contains(":")) {
              first = message.lastIndexOf(":");
              message = message.substring(first + 2).toLowerCase();
              if (Utils.isStringNotNullOrEmpty(message)
                  && message.trim().equalsIgnoreCase("Null body")) {
                message = Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "NoRequestBody");
              }
            }
          }
        }
      }
      finalMessage += message;
      JsonObject errorObject = new JsonObject()
          .put(Field.CODE.getName(), HttpStatus.SC_BAD_REQUEST)
          .put(Field.MESSAGE.getName(), finalMessage);
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
          .end(errorObject.encode());

      DynamoBusModBase.log.error(message);
    });

    router.errorHandler(HttpStatus.SC_UNAUTHORIZED, routingContext -> {
      Level level = Level.ERROR;
      String finalMessage = requestFailedPrefix;
      int statusCode = routingContext.statusCode();
      if (routingContext.failure() != null) {
        String sessionId = getSessionCookie(routingContext);
        if (sessionId == null) {
          finalMessage += Utils.getLocalizedString(
              RequestUtils.getLocale(routingContext), "SessionIdMustBeSpecified");
          level = Level.INFO;
        } else {
          String message = AuthProvider.getSessionErrorMessage(sessionId);
          if (message != null) {
            if (message.equals(AuthProvider.AuthErrorCodes.SESSION_NOT_FOUND.name())) {
              finalMessage += Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "SessionNotFound");
              level = Level.INFO;
            } else if (message.equals(AuthProvider.AuthErrorCodes.USER_NOT_FOUND.name())) {
              finalMessage += Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "NoSuchUserInTheDatabase");
              statusCode = HttpStatus.SC_BAD_REQUEST;
            } else if (message.equals(AuthProvider.AuthErrorCodes.USER_DISABLED.name())) {
              finalMessage += Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "TheUserIsDisabled");
              statusCode = HttpStatus.SC_FORBIDDEN;
            }
            AuthProvider.deleteSessionErrorMessage(sessionId);
          } else {
            if (routingContext.failure().getCause() != null
                && routingContext.failure().getCause().getMessage() != null) {
              finalMessage += routingContext.failure().getCause().getMessage();
            } else if (routingContext.failure().getMessage() != null) {
              finalMessage += routingContext.failure().getMessage();
            } else {
              finalMessage += HttpResponseStatus.UNAUTHORIZED.reasonPhrase();
            }
          }
        }
      } else {
        finalMessage += HttpResponseStatus.UNAUTHORIZED.reasonPhrase();
      }
      // not adding for 403 - user is disabled
      if (statusCode != HttpStatus.SC_FORBIDDEN) {
        routingContext.response().putHeader("Authenticate", AuthManager.getAuthHeader());
      }

      JsonObject errorObject = new JsonObject()
          .put(Field.CODE.getName(), statusCode)
          .put(Field.MESSAGE.getName(), finalMessage);
      routingContext
          .response()
          .setStatusCode(statusCode)
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
          .end(errorObject.encode());
      if (level.equals(Level.INFO)) {
        DynamoBusModBase.log.info(finalMessage);
      } else {
        DynamoBusModBase.log.error(finalMessage);
      }
    });

    router.errorHandler(HttpStatus.SC_NOT_FOUND, routingContext -> {
      sendErrorResponse(routingContext, HttpStatus.SC_NOT_FOUND, null);
    });

    router.errorHandler(HttpStatus.SC_INTERNAL_SERVER_ERROR, routingContext -> {
      sendErrorResponse(routingContext, HttpStatus.SC_INTERNAL_SERVER_ERROR, null);
    });

    router.errorHandler(HttpStatus.SC_SERVICE_UNAVAILABLE, routingContext -> {
      sendErrorResponse(routingContext, HttpStatus.SC_SERVICE_UNAVAILABLE, null);
    });

    router.errorHandler(HttpStatus.SC_TOO_MANY_REQUESTS, routingContext -> {
      String errorMessage = Utils.getLocalizedString(
          RequestUtils.getLocale(routingContext), "RateLimitExceededForRequests");
      sendErrorResponse(routingContext, HttpStatus.SC_SERVICE_UNAVAILABLE, errorMessage);
    });
  }

  private void sendErrorResponse(
      RoutingContext routingContext, int errorCode, String errorMessage) {
    String finalMessage = requestFailedPrefix;
    String message;
    if (!Utils.isStringNotNullOrEmpty(errorMessage)) {
      if (routingContext.failure() != null) {
        message = routingContext.failure().getMessage();
      } else {
        message = HttpResponseStatus.valueOf(errorCode).reasonPhrase();
      }
    } else {
      message = errorMessage;
    }
    finalMessage += message;
    JsonObject errorObject = new JsonObject()
        .put(Field.CODE.getName(), errorCode)
        .put(Field.MESSAGE.getName(), finalMessage);
    routingContext
        .response()
        .setStatusCode(errorCode)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
        .end(errorObject.encode());
    DynamoBusModBase.log.error(finalMessage);
  }

  private void doGetConfigurationProperties(RoutingContext routingContext) {
    String revision = config.getCustomProperties().getRevision();
    String licensingUrl = config.getCustomProperties().getLicensing();
    String websocketUrl = config.getCustomProperties().getWebsocketUrl();
    String dynamoDbPrefix = config.getCustomProperties().getDynamoDBPrefix();
    JsonObject json = new JsonObject();
    json.put("revision", revision);
    json.put("dbPrefix", dynamoDbPrefix);
    json.put("urls", new JsonObject().put("licensing", licensingUrl).put("ws", websocketUrl));
    routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    routingContext.response().setStatusCode(HttpStatus.SC_OK).end(json.encodePrettily());
  }

  /**
   * test request:
   * body {
   * fileId
   * }
   * header {
   * sessionId
   * }
   * uncomment for debugging
   **/
  private void doSendEmail(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = new JsonObject();
    try {
      body = routingContext.body().asJsonObject();
    } catch (Exception ignored) {
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        MailUtil.address + ".sendDemoMail",
        body,
        (Handler<AsyncResult<Message<JsonObject>>>) message2 -> simpleResponse(
            routingContext,
            message2,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotGetNotifications")));
  }

  private void doHandleLogs(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String sessionId = getSessionCookie(routingContext);
    if (sessionId == null) {
      sessionId = routingContext.request().getParam(Field.SESSION_ID.getName());
    }
    final String finalSessionId = sessionId;
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String address = emptyString;
          String path = routingContext.request().path();
          if (path.endsWith(Field.FILES.getName())) {
            address = ".getFilesLog";
          } else if (path.endsWith("subscriptions")) {
            address = ".getSubscriptionsLog";
          } else if (path.endsWith("performance")) {
            address = ".getPerformanceStats";
          } else if (path.contains("links") && path.endsWith("cached")) {
            address = ".getCachedLinks";
          } else if (path.endsWith("links")) {
            address = ".getOldLinks";
          } else if (path.contains("job")) {
            address = ".getJobStatus";
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              StatsVerticle.address + address,
              new JsonObject()
                  .put("from", routingContext.request().getParam("from"))
                  .put("to", routingContext.request().getParam("to"))
                  .put(Field.SESSION_ID.getName(), finalSessionId)
                  .put(
                      Field.USER_ID.getName(),
                      routingContext.request().getParam(Field.USER_ID.getName()))
                  .put(
                      Field.ACTION.getName(),
                      routingContext.request().getParam(Field.ACTION.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      routingContext.request().getParam(Field.STORAGE_TYPE.getName()))
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      routingContext.request().getParam(Field.EXTERNAL_ID.getName()))
                  .put(
                      Field.JOB_ID.getName(),
                      routingContext.request().getParam(Field.JOB_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                if (message1.result() == null) {
                  routingContext
                      .response()
                      .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                      .end(new JsonObject()
                          .put(Field.MESSAGE.getName(), message1.cause().toString())
                          .encodePrettily());
                } else if (message1
                    .result()
                    .body()
                    .getString(Field.STATUS.getName())
                    .equals(OK)) {
                  routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                  routingContext
                      .response()
                      .end(message1
                          .result()
                          .body()
                          .getJsonObject(Field.RESULT.getName())
                          .encodePrettily());
                } else {
                  simpleResponse(routingContext, message1, emptyString);
                }
              });
        });
  }

  private void doFileCheckin(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String fileId = routingContext.request().params().get(Field.FILE_ID.getName());
    String requestToken = routingContext.request().headers().get("requestToken");
    if (Utils.isStringNotNullOrEmpty(requestToken)) {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          TrimbleConnect.address + ".checkin",
          new JsonObject().put("requestToken", requestToken).put(Field.FILE_ID.getName(), fileId),
          (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
              routingContext,
              event,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotCheckInFile")));
    } else {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "RequestTokenMustBeSpecified"))
              .encodePrettily());
    }
  }

  private void doGetIntegrations(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".integration",
        new JsonObject(),
        messageAsyncResult -> simpleResponse(messageAsyncResult, routingContext, emptyString));
  }

  private void doStoreConnection(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    StorageType storageType =
        StorageType.getStorageType(routingContext.request().getParam("storage"));
    JsonObject requestBody = Utils.getBodyAsJson(routingContext);

    String sessionId = getSessionCookie(routingContext);
    boolean isAuthenticated = false;
    if (Utils.isStringNotNullOrEmpty(sessionId)) {
      isAuthenticated = new AuthProvider().authenticate(routingContext, sessionId, false);
    }
    if (isAuthenticated) {
      if (!isUserAdminAndIsStorageAvailable(routingContext)) {
        return;
      }

      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".additionalAuth",
          AuthManager.getAuthData(routingContext),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> {
            if (!isAuthSuccessful(routingContext, message)) {
              return;
            }
            requestBody.mergeIn(message.result().body());
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                StorageType.getAddress(storageType) + ".storeConnection",
                requestBody,
                (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                    routingContext,
                    event,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotSwitchToAnotherStorage")));
          });
    } else {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          StorageType.getAddress(storageType) + ".storeConnection",
          requestBody,
          (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
              routingContext,
              event,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotSwitchToAnotherStorage")));
    }
  }

  private void doCompareDrawings(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject requestBody = Utils.getBodyAsJson(routingContext);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          requestBody.mergeIn(message.result().body());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ThumbnailsManager.address + ".compareDrawings",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSwitchToAnotherStorage")));
        });
  }

  private void doFlushLogs(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject requestBody = Utils.getBodyAsJson(routingContext);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              StatsVerticle.address + ".flushLogs",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>)
                  event -> simpleResponse(routingContext, event, "CouldNotFlushLogs"));
        });
  }

  private void doSavePerformanceStats(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject requestBody = Utils.getBodyAsJson(routingContext);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          requestBody.mergeIn(message.result().body());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              StatsVerticle.address + ".savePerformanceStat",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSwitchToAnotherStorage")));
        });
  }

  private void doRedirectToFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        MailUtil.address + ".getFileRedirectURL",
        new JsonObject()
            .put(
                Field.EMAIL.getName(),
                routingContext.request().getParam(Field.EMAIL.getName()).toLowerCase())
            .put(Field.STORAGE_TYPE.getName(), routingContext.request().getParam("st"))
            .put(
                Field.EXTERNAL_ID.getName(),
                routingContext.request().getParam(Field.EXTERNAL_ID.getName()))
            .put(
                Field.OBJECT_ID.getName(),
                routingContext.request().getParam(Field.OBJECT_ID.getName()))
            .put(Field.TOKEN.getName(), routingContext.request().getParam(Field.TOKEN.getName())),
        (Handler<AsyncResult<Message<String>>>) response -> {
          routingContext
              .response()
              .putHeader(Field.LOCATION.getName(), response.result().body())
              .setStatusCode(302)
              .end();
        });
  }

  private void doGenerateToken(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        GridFSModule.address + ".getToken",
        new JsonObject()
            .put(
                "parameters",
                Objects.nonNull(routingContext.body())
                    ? routingContext.body().asJsonObject()
                    : new JsonObject())
            .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
        (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
            routingContext,
            event,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotGetAccessToken")));
  }

  private void doGetEmailImage(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        MailUtil.address + ".getEmailImage",
        new JsonObject()
            .put(
                Field.EMAIL.getName(),
                routingContext.request().getParam(Field.EMAIL.getName()).toLowerCase())
            .put(Field.STORAGE_TYPE.getName(), routingContext.request().getParam("st"))
            .put(Field.FILE_ID.getName(), routingContext.request().getParam("fid"))
            .put(
                Field.OBJECT_ID.getName(),
                routingContext.request().getParam(Field.OBJECT_ID.getName())),
        imageResponse -> {
          byte[] imageData = ((byte[]) imageResponse.result().body());
          routingContext
              .response()
              .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(imageData.length))
              .putHeader(HttpHeaders.CONTENT_TYPE, "image/png")
              .putHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=\"unnamed.png\"");
          routingContext.response().write(Buffer.buffer(imageData));
          routingContext.response().end();
        });
  }

  private void doSendFeedback(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject jsonObject = message.result().body();
          String comment = String.format(
              "User: %s %s %s<br> Subject: %s<br> Comment: %s",
              jsonObject.containsKey(Field.F_NAME.getName())
                  ? jsonObject.getString(Field.F_NAME.getName())
                  : emptyString,
              jsonObject.containsKey(Field.SURNAME.getName())
                  ? jsonObject.getString(Field.SURNAME.getName())
                  : emptyString,
              jsonObject.getString(Field.USERNAME.getName()),
              body.getString("subject"),
              body.getString("comment"));
          body.put(
                  "subject",
                  String.format(
                      "DraftSight Web - Feedback from %s",
                      jsonObject.getString(Field.USERNAME.getName())))
              .put("email_property", "feedback_email")
              .put("body", comment);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              MailUtil.address + ".customEmail",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSendFeedback")));
        });
  }

  private void doGetCompany(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String companyId = routingContext.request().getParam("companyId");
    if (!isUserAdminAndIsStorageAvailable(
        routingContext, config.getCustomProperties().getEnterprise())) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getCompany",
              message.result().body().put(Field.ID.getName(), companyId),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetCompany")));
        });
  }

  private void doUpdateCompany(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.put(Field.ID.getName(), routingContext.request().getParam("companyId"));
    if (!isUserAdminAndIsStorageAvailable(
        routingContext, config.getCustomProperties().getEnterprise())) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".updateCompany",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateCompany")));
        });
  }

  private void doDeleteComment(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String id = (String) routingContext.data().get(Field.ID.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = new JsonObject()
        .mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(id, routingContext.request().getParam("annotationId"))
        .put(
            Field.COMMENT_ID.getName(),
            routingContext.request().getParam(Field.COMMENT_ID.getName()))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteComment")));
        });
  }

  private void doUpdateComment(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String id = (String) routingContext.data().get(Field.ID.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(id, routingContext.request().getParam("annotationId"))
        .put(
            Field.COMMENT_ID.getName(),
            routingContext.request().getParam(Field.COMMENT_ID.getName()))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateComment")));
        });
  }

  private void doAddComment(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String id = (String) routingContext.data().get(Field.ID.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(id, routingContext.request().getParam("annotationId"))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotAddComment")));
        });
  }

  private void doDeleteAnnotation(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String id = (String) routingContext.data().get(Field.ID.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = new JsonObject()
        .put(
            Field.TIMESTAMP.getName(), routingContext.request().getParam(Field.TIMESTAMP.getName()))
        .put(id, routingContext.request().getParam("annotationId"))
        .mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteThread")));
        });
  }

  private void doUpdateAnnotation(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String id = (String) routingContext.data().get(Field.ID.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(id, routingContext.request().getParam("annotationId"))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateThread")));
        });
  }

  private void doGetAnnotation(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String id = (String) routingContext.data().get(Field.ID.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(id, routingContext.request().getParam("annotationId"))
        .put(
            Field.TIMESTAMP.getName(), routingContext.request().getParam(Field.TIMESTAMP.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetThread")));
        });
  }

  private void doAddAnnotation(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotAddThread")));
        });
  }

  private void doGetAnnotations(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String returnDeleted = routingContext.request().getHeader("returnDeleted");
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String address = (String) routingContext.data().get("address");
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(
            Field.TIMESTAMP.getName(), routingContext.request().getParam(Field.TIMESTAMP.getName()))
        .put("returnDeleted", returnDeleted == null || Boolean.parseBoolean(returnDeleted))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + "." + address,
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetThreads")));
        });
  }

  private void doGetAllAnnotations(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String returnDeleted = routingContext.request().getHeader("returnDeleted");
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put(Field.TOKEN.getName(), token)
        .put(
            Field.TIMESTAMP.getName(), routingContext.request().getParam(Field.TIMESTAMP.getName()))
        .put("returnDeleted", returnDeleted == null || Boolean.parseBoolean(returnDeleted))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + ".getCommentThreads",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) commentsEvent -> {
                if (commentsEvent.succeeded()) {
                  eb_request_with_metrics(
                      optionalSegment.orElse(null),
                      routingContext,
                      CommentVerticle.address + ".getMarkups",
                      message.result().body().mergeIn(body),
                      (Handler<AsyncResult<Message<JsonObject>>>) markupsEvent -> {
                        if (markupsEvent.succeeded()) {
                          if (markupsEvent
                              .result()
                              .body()
                              .getString(Field.STATUS.getName())
                              .equals(OK)) {
                            JsonObject markupsBody = markupsEvent.result().body();
                            JsonObject commentsBody = commentsEvent.result().body();
                            int statusCode =
                                markupsBody.getInteger(Field.STATUS_CODE.getName()) != null
                                    ? markupsBody.getInteger(Field.STATUS_CODE.getName())
                                    : HttpStatus.SC_OK;
                            long timestamp = Math.max(
                                markupsBody.getLong(Field.TIMESTAMP.getName()),
                                commentsBody.getLong(Field.TIMESTAMP.getName()));
                            JsonObject unifiedResponse = markupsBody
                                .mergeIn(commentsBody)
                                .put(Field.TIMESTAMP.getName(), timestamp);
                            routingContext
                                .response()
                                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                            routingContext
                                .response()
                                .setStatusCode(statusCode)
                                .end(unifiedResponse.encodePrettily());
                          } else {
                            simpleResponse(
                                routingContext,
                                markupsEvent,
                                Utils.getLocalizedString(
                                    RequestUtils.getLocale(routingContext), "CouldNotGetThreads"));
                          }
                        } else {
                          simpleResponse(
                              routingContext,
                              markupsEvent,
                              Utils.getLocalizedString(
                                  RequestUtils.getLocale(routingContext), "CouldNotGetThreads"));
                        }
                      });
                } else {
                  simpleResponse(
                      routingContext,
                      commentsEvent,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetThreads"));
                }
              });
        });
  }

  private void doGetNotifications(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              NotificationEvents.address + ".getNotifications",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetNotifications")));
        });
  }

  private void doGetAttachments(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(new JsonObject()).put(Field.TOKEN.getName(), token);
    body.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + ".getAttachments",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetAttachments")));
        });
  }

  private void doGetAttachment(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    boolean preview = (Boolean) routingContext.data().get(Field.PREVIEW.getName());
    JsonObject body = Utils.getBodyAsJson(routingContext);

    body.mergeIn(new JsonObject())
        .put(Field.TOKEN.getName(), token)
        .put("attachmentId", routingContext.request().getParam("attachmentId"))
        .put(Field.PREVIEW.getName(), preview)
        .put(
            Field.X_SESSION_ID.getName(),
            routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
        .put(Field.PASSWORD.getName(), password);
    body.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + ".getAttachment",
              message.result().body().mergeIn(body),
              message1 -> {
                if (message1.result() == null) {
                  routingContext
                      .response()
                      .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                      .end(new JsonObject()
                          .put(Field.MESSAGE.getName(), message1.cause().toString())
                          .encodePrettily());
                  return;
                }
                JsonObject obj = (JsonObject) message1.result().body();
                if (obj.getString(Field.STATUS.getName()).equals(OK)) {
                  routingContext.response().putHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                  byte[] data = obj.getBinary(Field.DATA.getName());
                  String contentType = obj.getString("contentType");
                  routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, contentType);
                  routingContext
                      .response()
                      .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(data.length));
                  try {
                    String tags = obj.getString("tags");
                    if (!tags.isEmpty()) {
                      routingContext.response().putHeader("Content-Tags", headerEncode(tags));
                    }
                  } catch (Exception exception) {
                    DynamoBusModBase.log.warn("Error converting tags" + exception.getMessage());
                  }
                  try {
                    JsonArray transcript = obj.containsKey("transcript")
                        ? obj.getJsonArray("transcript")
                        : new JsonArray();
                    if (transcript != null && !transcript.isEmpty()) {
                      routingContext
                          .response()
                          .putHeader("Content-Transcript", headerEncode(transcript.encode()));
                    }
                  } catch (Exception exception) {
                    DynamoBusModBase.log.warn(
                        "Error converting transcript" + exception.getMessage());
                  }
                  routingContext.response().write(Buffer.buffer(data));
                  routingContext.response().end();
                } else {
                  routingContext
                      .response()
                      .setStatusCode(obj.getInteger(Field.STATUS_CODE.getName()))
                      .end(Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetAttachments"));
                }
              });
        });
  }

  private void doGetAttachmentDescription(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    JsonObject body = new JsonObject();
    try {
      body = Utils.getBodyAsJson(routingContext);
    } catch (Exception ignore) {
    }
    body.mergeIn(new JsonObject())
        .put(Field.TOKEN.getName(), token)
        .put("attachmentId", routingContext.request().getParam("attachmentId"))
        .put(Field.PASSWORD.getName(), password);
    body.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    JsonObject finalBody = body;
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              CommentVerticle.address + ".getAttachmentDescription",
              message.result().body().mergeIn(finalBody),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetAttachments")));
        });
  }

  private void doAddAttachment(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String password = routingContext.request().getHeader(Field.PASSWORD.getName());
    String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          if (routingContext.fileUploads() != null
              && !routingContext.fileUploads().isEmpty()) {
            Iterator<FileUpload> it = routingContext.fileUploads().iterator();
            JsonObject body = message.result().body();
            body.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
                .put(
                    Field.X_SESSION_ID.getName(),
                    routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
                .put(Field.TOKEN.getName(), token)
                .put(Field.PASSWORD.getName(), password);
            while (it.hasNext()) {
              FileUpload fileUpload = it.next();
              Buffer uploaded = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
              String contentType =
                  routingContext.request().headers().get("contentType"); // custom for local testing
              String detectedContentType =
                  URLConnection.guessContentTypeFromName(fileUpload.fileName());
              body.put(
                  "contentType",
                  Utils.isStringNotNullOrEmpty(detectedContentType)
                      ? detectedContentType
                      : contentType);
              eb_request(
                  optionalSegment.orElse(null),
                  CommentVerticle.address + ".addAttachment",
                  MessageUtils.generateBuffer(
                      optionalSegment.orElse(null),
                      body,
                      uploaded.getBytes(),
                      RequestUtils.getLocale(routingContext)),
                  (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                      routingContext,
                      event,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotAddAttachment")));
            }
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "ThereIsNoFileInTheRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doMarkNotifications(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              NotificationEvents.address + ".markNotificationsAsRead",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotPutNotifications")));
        });
  }

  private void doGetFileNotifications(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = new JsonObject();
    try {
      body = Utils.getBodyAsJson(routingContext);
    } catch (Exception ignore) {
    }
    body.mergeIn(Utils.parseItemId(
        routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()));
    JsonObject finalBody = body;
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              NotificationEvents.address + ".getFileNotifications",
              message.result().body().mergeIn(finalBody),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetNotifications")));
        });
  }

  private void doCreateFileShortcut(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    JsonObject requestBody = new JsonObject() {
      {
        JsonObject jsonObject = Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.OBJECT_ID.getName());

        JsonObject body = Utils.getBodyAsJson(routingContext);

        put(Field.NAME.getName(), body.getString(Field.NAME.getName()));
        put("createInCurrentFolder", body.getBoolean("createInCurrentFolder", true));
        put(Field.OBJECT_ID.getName(), jsonObject.getString(Field.OBJECT_ID.getName()));
        put(Field.STORAGE_TYPE.getName(), jsonObject.getString(Field.STORAGE_TYPE.getName()));
        put(Field.EXTERNAL_ID.getName(), jsonObject.getString(Field.EXTERNAL_ID.getName()));
      }
    };

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".createShortcut",
              requestBody.put(
                  Field.USER_ID.getName(),
                  message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCreateShortcut")));
        });
  }

  private void doCreateFolderShortcut(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    JsonObject requestBody = new JsonObject() {
      {
        JsonObject jsonObject = Utils.parseItemId(
            routingContext.request().getParam(Field.FOLDER_ID.getName()),
            Field.OBJECT_ID.getName());

        JsonObject body = Utils.getBodyAsJson(routingContext);

        put(Field.NAME.getName(), body.getString(Field.NAME.getName()));
        put("createInCurrentFolder", body.getBoolean("createInCurrentFolder", true));
        put(Field.OBJECT_ID.getName(), jsonObject.getString(Field.OBJECT_ID.getName()));
        put(Field.STORAGE_TYPE.getName(), jsonObject.getString(Field.STORAGE_TYPE.getName()));
        put(Field.EXTERNAL_ID.getName(), jsonObject.getString(Field.EXTERNAL_ID.getName()));
      }
    };

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".createShortcut",
              requestBody.put(
                  Field.USER_ID.getName(),
                  message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCreateShortcut")));
        });
  }

  //    private void doGetSubscriptions(RoutingContext routingContext) {
  //        Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
  //        JsonObject body = new JsonObject();
  //        try {
  //            body = Utils.getBodyAsJson(routingContext);
  //        } catch (Exception ignore) {
  //        }
  //        JsonObject finalBody = body;
  //        auth(segment, routingContext, false, true, message -> {
  //            if (message.succeeded() && message.result().body().getString(Field.STATUS.getName
  //            ()).equals(OK)) {
  //                ebRequestWithMetrics()(optionalSegment.orElse(null), routingContext,
  //                Subscriptions.address + ".getSubscriptions",
  //                        message.result().body().mergeIn(finalBody),
  //                        (Handler<AsyncResult<Message<JsonObject>>>) event ->
  //                                simpleResponse(routingContext, event, Utils
  //                                .getLocalizedString(routingContext.request().headers().get
  //                                (Field.LOCALE.getName()), "CouldNotGetSubscription")));
  //            } else
  //                simpleResponse(routingContext, message, Utils.getLocalizedString
  //                (RequestUtils.getLocale(routingContext), "CouldNotGetSubscription"));
  //        });
  //    }

  private void doMarkFileNotifications(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);
    body.mergeIn(Utils.parseItemId(
        routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()));

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              NotificationEvents.address + ".markFileNotificationsAsRead",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotPutNotifications")));
        });
  }

  private void doGetSubscription(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              Subscriptions.address + ".getSubscription",
              message
                  .result()
                  .body()
                  .mergeIn(Utils.parseItemId(
                      routingContext.request().getParam(Field.FILE_ID.getName()),
                      Field.FILE_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetSubscription")));
        });
  }

  private void doAddSubscription(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext)
        .mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()));

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject subscriptionRequest = message.result().body().mergeIn(body);
          subscriptionRequest
              .put("scope", new JsonArray().add(Subscriptions.subscriptionScope.GLOBAL.toString()))
              .put("scopeUpdate", Subscriptions.scopeUpdate.REWRITE.toString())
              .put("manual", true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              Subscriptions.address + ".addSubscription",
              subscriptionRequest,
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotAddSubscription")));
        });
  }

  private void doDeleteSubscription(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = new JsonObject()
        .mergeIn(Utils.parseItemId(
            routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName()))
        .put("source", routingContext.request().getParam("source"));
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject subscriptionDeleteRequest = message.result().body().mergeIn(body);
          subscriptionDeleteRequest.put("manual", true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              Subscriptions.address + ".deleteSubscription",
              subscriptionDeleteRequest,
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteSubscription")));
        });
  }

  private void doGetFileLinks(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            GridFSModule.address + ".getFileLinks",
            new JsonObject()
                .put(
                    Field.FILE_ID.getName(),
                    routingContext.request().getParam(Field.FILE_ID.getName()))
                .put(
                    Field.USER_ID.getName(),
                    message.result().body().getString(Field.USER_ID.getName()))
                .put(
                    Field.STORAGE_TYPE.getName(),
                    message.result().body().getString(Field.STORAGE_TYPE.getName()))
                .put(
                    Field.EXTERNAL_ID.getName(),
                    message.result().body().getString(Field.EXTERNAL_ID.getName())),
            (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                routingContext,
                message1,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotGetFileLink"))));
  }

  private void doDeleteFileVersionViewLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            GridFSModule.address + ".deleteFileVersionViewLink",
            new JsonObject()
                .put(
                    Field.FILE_ID.getName(),
                    routingContext.request().getParam(Field.FILE_ID.getName()))
                .put(
                    Field.VERSION_ID.getName(),
                    routingContext.request().getParam(Field.VERSION_ID.getName()))
                .put(
                    Field.SHEET_ID.getName(),
                    routingContext.request().getParam(Field.SHEET_ID.getName()))
                .put(
                    Field.USER_ID.getName(),
                    message.result().body().getString(Field.USER_ID.getName()))
                .put(
                    Field.STORAGE_TYPE.getName(),
                    message.result().body().getString(Field.STORAGE_TYPE.getName()))
                .put(
                    Field.EXTERNAL_ID.getName(),
                    message.result().body().getString(Field.EXTERNAL_ID.getName())),
            (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                routingContext,
                message1,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotRemoveSharedLink"))));
  }

  private void doDeleteFileVersionDownloadLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            GridFSModule.address + ".deleteFileVersionDownloadLink",
            new JsonObject()
                .put(
                    Field.FILE_ID.getName(),
                    routingContext.request().getParam(Field.FILE_ID.getName()))
                .put(
                    Field.VERSION_ID.getName(),
                    routingContext.request().getParam(Field.VERSION_ID.getName()))
                .put(
                    Field.SHEET_ID.getName(),
                    routingContext.request().getParam(Field.SHEET_ID.getName()))
                .put(
                    Field.USER_ID.getName(),
                    message.result().body().getString(Field.USER_ID.getName()))
                .put(
                    Field.STORAGE_TYPE.getName(),
                    message.result().body().getString(Field.STORAGE_TYPE.getName()))
                .put(
                    Field.EXTERNAL_ID.getName(),
                    message.result().body().getString(Field.EXTERNAL_ID.getName())),
            (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                routingContext,
                message1,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotRemoveSharedLink"))));
  }

  private void doGetFileVersionViewLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          JsonObject body = Utils.getBodyAsJson(routingContext);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getFileVersionViewLink",
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put(
                      Field.VERSION_ID.getName(),
                      routingContext.request().getParam(Field.VERSION_ID.getName()))
                  .put(
                      Field.SHEET_ID.getName(),
                      routingContext.request().getParam(Field.SHEET_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      message.result().body().getString(Field.STORAGE_TYPE.getName()))
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      message.result().body().getString(Field.EXTERNAL_ID.getName()))
                  .put(Field.EXPORT.getName(), body.getBoolean(Field.EXPORT.getName(), false))
                  .put(Field.END_TIME.getName(), body.getLong(Field.END_TIME.getName(), 0L))
                  .put(Field.PASSWORD.getName(), body.getString(Field.PASSWORD.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetSharedLink")));
        });
  }

  private void doGetFileVersionDownloadLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          JsonObject body = Utils.getBodyAsJson(routingContext);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getFileVersionDownloadLink",
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put(
                      Field.VERSION_ID.getName(),
                      routingContext.request().getParam(Field.VERSION_ID.getName()))
                  .put(
                      Field.SHEET_ID.getName(),
                      routingContext.request().getParam(Field.SHEET_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      message.result().body().getString(Field.STORAGE_TYPE.getName()))
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      message.result().body().getString(Field.EXTERNAL_ID.getName()))
                  .put("convertToPdf", body.getBoolean("convertToPdf", false))
                  .put(Field.EXPORT.getName(), body.getBoolean(Field.EXPORT.getName(), false))
                  .put(Field.END_TIME.getName(), body.getLong(Field.END_TIME.getName(), 0L))
                  .put(Field.PASSWORD.getName(), body.getString(Field.PASSWORD.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetSharedLink")));
        });
  }

  private void doGetSharedLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }

          String endTime = routingContext.request().getHeader(Field.END_TIME.getName());
          if (endTime == null || endTime.isEmpty()) {
            endTime = "0";
          }
          String password = routingContext.request().getHeader(Field.PASSWORD.getName());
          Boolean resetPassword = Boolean.parseBoolean(
              routingContext.request().getHeader(Field.RESET_PASSWORD.getName()));
          if (!Utils.isStringNotNullOrEmpty(password)) {
            password = emptyString;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getSharedLink",
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.GRAEBERT_ID.getName(),
                      message.result().body().getString(Field.GRAEBERT_ID.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      message.result().body().getString(Field.STORAGE_TYPE.getName()))
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      message.result().body().getString(Field.EXTERNAL_ID.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.USERNAME.getName(),
                      message.result().body().getString(Field.USERNAME.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.INTERCOM_ACCESS_TOKEN.getName(),
                      message.result().body().getString(Field.INTERCOM_ACCESS_TOKEN.getName()))
                  .put(
                      Field.EXPORT.getName(),
                      Boolean.parseBoolean(
                          routingContext.request().getHeader(Field.EXPORT.getName())))
                  .put(Field.END_TIME.getName(), Long.parseLong(endTime))
                  .put(Field.PASSWORD.getName(), password)
                  .put(Field.RESET_PASSWORD.getName(), resetPassword),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetSharedLink")));
        });
  }

  private void doUpdateSharedLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String endTime = routingContext.request().getHeader(Field.END_TIME.getName());
          if (endTime == null || endTime.isEmpty()) {
            endTime = "0";
          }
          String password = routingContext.request().getHeader(Field.PASSWORD.getName());
          if (!Utils.isStringNotNullOrEmpty(password)) {
            password = emptyString;
          }
          boolean isResetEndTime =
              Boolean.parseBoolean(routingContext.request().getHeader("resetEndTime"));
          boolean isResetPassword = Boolean.parseBoolean(
              routingContext.request().getHeader(Field.RESET_PASSWORD.getName()));

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".updateSharedLink",
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.GRAEBERT_ID.getName(),
                      message.result().body().getString(Field.GRAEBERT_ID.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      message.result().body().getString(Field.STORAGE_TYPE.getName()))
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      message.result().body().getString(Field.EXTERNAL_ID.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.USERNAME.getName(),
                      message.result().body().getString(Field.USERNAME.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.INTERCOM_ACCESS_TOKEN.getName(),
                      message.result().body().getString(Field.INTERCOM_ACCESS_TOKEN.getName()))
                  .put(
                      Field.EXPORT.getName(),
                      Boolean.parseBoolean(
                          routingContext.request().getHeader(Field.EXPORT.getName())))
                  .put(Field.END_TIME.getName(), Long.parseLong(endTime))
                  .put(Field.PASSWORD.getName(), password)
                  .put("resetEndTime", isResetEndTime)
                  .put(Field.RESET_PASSWORD.getName(), isResetPassword),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetSharedLink")));
        });
  }

  private void doSendSharedLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          JsonArray data = body.getJsonArray(Field.DATA.getName());
          if (data == null || data.isEmpty()) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "DataIsRequired"))
                    .encodePrettily());
          } else {
            eb_send_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                MailUtil.address + ".sendSharedLink",
                new JsonObject()
                    .put(Field.DATA.getName(), data)
                    .put(
                        Field.FILE_ID.getName(),
                        routingContext.request().getParam(Field.FILE_ID.getName()))
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName())));
          }
        });
  }

  private void doRemoveSharedLink(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".removeSharedLink",
              message
                  .result()
                  .body()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotRemoveSharedLink")));
        });
  }

  private void doExtendPermission(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".graebertLogin",
        body.put(Field.TOKEN.getName(), routingContext.request().getHeader(Field.TOKEN.getName()))
            .put("deviceId", routingContext.request().getHeader("deviceId"))
            .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent"))
            .put("extendPermission", true),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotExtendPermission")));
  }

  private void doSaveSamlResponse(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".saveSamlResponse",
        body,
        (Handler<AsyncResult<Message<JsonObject>>>)
            message -> simpleResponse(routingContext, message, "Could not save saml response"));
  }

  private void doSSOLogin(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (routingContext.request().getHeader(Field.SESSION_ID.getName()) != null) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject().put(Field.MESSAGE.getName(), "alreadyLoggedIn").encodePrettily());
    } else {
      JsonObject body = Utils.getBodyAsJson(routingContext);

      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".ssoLogin",
          body.put(Field.TOKEN.getName(), routingContext.request().getHeader(Field.TOKEN.getName()))
              .put("deviceId", routingContext.request().getHeader("deviceId"))
              .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext),
                  "CouldNotLoginUsingGraebertCredentials")));
    }
  }

  private void doVerify(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String sessionId = getSessionCookie(routingContext);

    boolean isAuthenticated = false;
    if (Utils.isStringNotNullOrEmpty(sessionId)) {
      isAuthenticated = new AuthProvider().authenticate(routingContext, sessionId, false);
    }
    if (isAuthenticated) {
      if (!isUserAdminAndIsStorageAvailable(routingContext)) {
        return;
      }

      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".additionalAuth",
          AuthManager.getAuthData(routingContext),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> {
            if (!isAuthSuccessful(routingContext, message)) {
              return;
            }
            verify(routingContext);
          });
    } else {
      verify(routingContext);
    }
  }

  private void verify(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    // Graebert and solidworks login
    String samlResponse = routingContext.request().getHeader("SAMLResponse");
    if (samlResponse != null) {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".samlLogin",
          new JsonObject()
              .put("samlResponse", samlResponse)
              .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotLoginUsingSaml")));
    } else {
      JsonObject body = new JsonObject();
      try {
        body = Utils.getBodyAsJson(routingContext);
      } catch (Exception ignore) {
      }

      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".graebertLogin",
          body.put(Field.TOKEN.getName(), routingContext.request().getHeader(Field.TOKEN.getName()))
              .put("deviceId", routingContext.request().getHeader("deviceId"))
              .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext),
                  "CouldNotLoginUsingGraebertCredentials")));
    }
  }

  private void doDeleteForeign(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          String password = body.getString(Field.PASSWORD.getName());
          String passconfirm = body.getString("passconfirm");
          if (password == null
              || password.trim().isEmpty()
              || passconfirm == null
              || passconfirm.trim().isEmpty()
              || !password.trim().equals(passconfirm.trim())) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "password confirmation doesn't match password")
                    .encodePrettily());
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".unlinkForeign",
                new JsonObject()
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(Field.PASSWORD.getName(), password),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUnlinkForeignAccount")));
          }
        });
  }

  private void doCheckXRefPath(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);
          JsonArray path = body.getJsonArray(Field.PATH.getName());

          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".checkXRefPath",
              message
                  .result()
                  .body()
                  .put(Field.PATH.getName(), path)
                  .put(
                      Field.FOLDER_ID.getName(),
                      routingContext.request().getParam(Field.FOLDER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetXref")));
        });
  }

  private void doGetXRef(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);
    JsonArray path = body.getJsonArray(Field.PATH.getName());

    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    if (token == null || token.trim().isEmpty()) {
      // 07.12 changes for box prototype; later switch to new encapsulation system
      JsonObject jsonId = Utils.parseItemId(
          routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName());
      String mode = jsonId.getString("encapsulationMode");
      if (mode != null && mode.equals("0")) {
        String fileId = jsonId.getString(Field.FILE_ID.getName());
        String storageType = jsonId.getString(Field.STORAGE_TYPE.getName());
        String externalId = jsonId.getString(Field.EXTERNAL_ID.getName());
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            GridFSModule.address + ".findXRef",
            new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
                .put(Field.USER_ID.getName(), "BOX_USER") // just in case
                .put(Field.IS_ADMIN.getName(), false)
                .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                .put(Field.PATH.getName(), path)
                .put(Field.STORAGE_TYPE.getName(), storageType)
                .put(Field.EXTERNAL_ID.getName(), externalId)
                .put("encapsulationMode", mode),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
                routingContext,
                message,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotGetInfoOfTheObject")));

      } else {
        String sessionId = getSessionCookie(routingContext);
        if (sessionId != null) {
          if (!new AuthProvider().authenticate(routingContext, sessionId)) {
            return;
          }

          if (!isUserAdminAndIsStorageAvailable(routingContext)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              AuthManager.address + ".additionalAuth",
              AuthManager.getAuthData(routingContext),
              (Handler<AsyncResult<Message<JsonObject>>>) message -> {
                if (!isAuthSuccessful(routingContext, message)) {
                  return;
                }
                message
                    .result()
                    .body()
                    .mergeIn(Utils.parseItemId(
                        routingContext.request().getParam(Field.FILE_ID.getName()),
                        Field.FILE_ID.getName()));
                eb_request_with_metrics(
                    optionalSegment.orElse(null),
                    routingContext,
                    GridFSModule.address + ".findXRef",
                    message
                        .result()
                        .body()
                        .put(
                            Field.FOLDER_ID.getName(),
                            routingContext.request().getParam(Field.FOLDER_ID.getName()))
                        .put(Field.PATH.getName(), path),
                    (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                        routingContext,
                        message1,
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CouldNotGetXref")));
              });
        } else {
          routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
        }
      }
    } else {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".findXRef",
          new JsonObject()
              .put(
                  Field.FILE_ID.getName(),
                  Utils.parseItemId(
                          routingContext.request().getParam(Field.FILE_ID.getName()),
                          Field.FILE_ID.getName())
                      .getString(Field.FILE_ID.getName()))
              .put(Field.PATH.getName(), path)
              .put(Field.TOKEN.getName(), token),
          (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
              routingContext,
              message1,
              Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "CouldNotGetXref")));
    }
  }

  private void doSwitchIntegrationAccount(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".switchExternalAccount",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSwitchToAnotherStorage")));
        });
  }

  private void doDeleteIntegrationAccount(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".deleteExternalAccount",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext),
                      "CouldNotDeleteAccountOfExternalStorage")));
        });
  }

  private void doGetMetrics(RoutingContext routingContext) {
    // for now without authentication.
    JsonObject json = new JsonObject();
    JsonObject vertxmetrics = metricsService.getMetricsSnapshot(vertx);
    json.put("vertx", vertxmetrics);
    JsonObject eventBusMetrics = metricsService.getMetricsSnapshot(eb);
    json.put("eventBus", eventBusMetrics);
    JsonObject serverMetrics = metricsService.getMetricsSnapshot(server);
    json.put(Field.SERVER.getName(), serverMetrics);
    routingContext.response().end(json.encodePrettily());
  }

  private void doGetRevision(RoutingContext routingContext) {
    String revision = config.getCustomProperties().getRevision();
    JsonObject json = new JsonObject();
    json.put("revision", revision);
    routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    routingContext.response().setStatusCode(HttpStatus.SC_OK).end(json.encodePrettily());
  }

  private void doGetAvailableStorages(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getAvailableStorages",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext),
                      "CouldNotGetAccountsOfExternalStorages")));
        });
  }

  private void doGetIntegrationSettings(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String sessionId = getSessionCookie(routingContext);
    String userId = emptyString;
    if (Utils.isStringNotNullOrEmpty(sessionId)) {
      Item session = Sessions.getSessionById(sessionId);
      if (session != null) {
        userId = Sessions.getUserIdFromPK(session.getString(Field.PK.getName()));
      }
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".getStoragesSettings",
        new JsonObject().put(Field.USER_ID.getName(), userId),
        (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
            routingContext,
            message1,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotGetAccountsOfExternalStorages")));
  }

  private void doGetFullExternalAccountsInfo(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject requestBody = message.result().body();
          requestBody.put(
              Field.TARGET_USER_ID.getName(),
              routingContext.request().params().get(Field.USER_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getFullExternalAccounts",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext),
                      "CouldNotGetAccountsOfExternalStorages")));
        });
  }

  private void doGetIntegrationAccounts(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getExternalAccounts",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext),
                      "CouldNotGetAccountsOfExternalStorages")));
        });
  }

  private void doAddStorage(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String redirectURL = routingContext.request().getHeader("redirect_url");
    final String storageName =
        routingContext.request().getParam(Field.TYPE.getName()).toUpperCase();
    final StorageType storageType = StorageType.getStorageType(storageName);
    if (redirectURL == null) {
      // set default to FL
      redirectURL = config.getCustomProperties().getUiUrl() + "notify/?mode=storage&type="
          + storageType.toString().toLowerCase()
          + "&code=";
    }
    if (storageType.equals(StorageType.INTERNAL) || storageType.equals(StorageType.SAMPLES)) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_NOT_FOUND)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "DataIsRequired"))
              .encodePrettily());
    } else {
      String finalRedirectURL = redirectURL;
      if (!isUserAdminAndIsStorageAvailable(routingContext)) {
        return;
      }
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".additionalAuth",
          AuthManager.getAuthData(routingContext),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> {
            if (!isAuthSuccessful(routingContext, message)) {
              return;
            }
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".integration",
                message.result().body(),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                  final String requiredClientId =
                      message1.result().body().getString(storageType.toString().toLowerCase());
                  if (requiredClientId == null) {
                    routingContext
                        .response()
                        .setStatusCode(HttpStatus.SC_NOT_FOUND)
                        .end(new JsonObject()
                            .put(
                                Field.MESSAGE.getName(),
                                Utils.getLocalizedString(
                                    RequestUtils.getLocale(routingContext), "DataIsRequired"))
                            .encodePrettily());
                  } else {
                    eb_request_with_metrics(
                        optionalSegment.orElse(null),
                        routingContext,
                        UsersVerticle.address + ".addStorage",
                        new JsonObject()
                            .put("redirectURL", finalRedirectURL)
                            .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                            .put("clientId", requiredClientId),
                        (Handler<AsyncResult<Message<JsonObject>>>) message2 -> {
                          if (message2.succeeded()
                              && message2
                                  .result()
                                  .body()
                                  .getString(Field.STATUS.getName())
                                  .equals(OK)) {
                            routingContext
                                .response()
                                .putHeader(
                                    "Location", message2.result().body().getString("finalURL"))
                                .setStatusCode(302)
                                .end();
                          } else {
                            simpleResponse(
                                routingContext,
                                message2,
                                Utils.getLocalizedString(
                                    RequestUtils.getLocale(routingContext),
                                    "CouldNotGetAccountsOfExternalStorages"));
                          }
                        });
                  }
                });
          });
    }
  }

  private void doGetOwners(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getOwners",
              message
                  .result()
                  .body()
                  .put(
                      Field.FOLDER_ID.getName(),
                      routingContext.request().getParam(Field.FOLDER_ID.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      message.result().body().getString(Field.STORAGE_TYPE.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetOwnersOfFolderContent")));
        });
  }

  private void doChangeEmail(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".changeEmail",
        body,
        (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
            routingContext,
            event,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "ChangeEmail.Failed")));
  }

  private void doChangeRegion(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".changeRegion",
              message
                  .result()
                  .body()
                  .put(
                      Field.S3_REGION.getName(),
                      routingContext.request().getHeader(Field.S3_REGION.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateRegion")));
        });
  }

  private void doGetRegions(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getRegions",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetRegions")));
        });
  }

  private void doTryReset(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".tryReset",
        body,
        (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
            routingContext,
            event,
            Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "TryResetFailed")));
  }

  private void doResetPwd(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".reset",
        body.put("ip", routingContext.request().remoteAddress().host())
            .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
        (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
            routingContext,
            event,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotResetPassword")));
  }

  private void doRequestResetPwd(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".requestReset",
        body,
        (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
            routingContext,
            event,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotRequestResettingPassword")));
  }

  private void doSendMsg(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body;
          try {
            body = Utils.getBodyAsJson(routingContext);
          } catch (Exception e) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE)
                .end();
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              MessagingManager.address + ".send",
              body.mergeIn(message.result().body()),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSendMessage")));
        });
  }

  private void doDeleteMsg(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              MessagingManager.address + ".delete",
              message.result().body().put("msgId", routingContext.request().getParam("msgId")),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteMessage")));
        });
  }

  //    private void doGetMsgs(RoutingContext routingContext) {
  //        Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
  //        auth(segment, routingContext, false, message -> {
  //            if (message.succeeded() && message.result().body().getString(Field.STATUS.getName
  //            ()).equals(OK)) {
  //                ebRequestWithMetrics()(optionalSegment.orElse(null), routingContext,
  //                MessagingManager.address + ".getAll",
  //                message.result().body(),
  //                        (Handler<AsyncResult<Message<JsonObject>>>) message1 ->
  //                                simpleResponse(routingContext, message1, Utils
  //                                .getLocalizedString(routingContext.request().headers().get
  //                                (Field.LOCALE.getName()), "CouldNotGetMessages")));
  //            } else
  //                simpleResponse(routingContext, message, Utils.getLocalizedString
  //                (RequestUtils.getLocale(routingContext), "CouldNotGetMessages"));
  //        });
  //    }

  private void doReadMsg(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              MessagingManager.address + ".read",
              message.result().body().put("msgId", routingContext.request().getParam("msgId")),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotReadUnreadMessage")));
        });
  }

  private void doCheckPortalExists(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String email = routingContext.request().getParam(Field.EMAIL.getName());
    if (email == null) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(Field.MESSAGE.getName(), "email must be specified")
              .encodePrettily());
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".checkEmail",
        new JsonObject().put(Field.EMAIL.getName(), email.toLowerCase()),
        (Handler<AsyncResult<Message<JsonObject>>>)
            message -> simpleResponse(routingContext, message, emptyString));
  }

  private void doCreatePortalUser(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    String email = body.getString(Field.EMAIL.getName());
    if (email != null) {
      body.put(Field.EMAIL.getName(), email.toLowerCase());
    }
    String password = body.getString(Field.PASSWORD.getName());
    String passconfirm = body.getString("passconfirm");
    String firstName = body.getString(Field.FIRST_NAME.getName());
    String lastName = body.getString(Field.LAST_NAME.getName());
    Integer country = body.getInteger("country");
    if (email == null
        || email.trim().isEmpty()
        || !email.matches(emailRegExp)
        || password == null
        || password.trim().isEmpty()
        || passconfirm == null
        || passconfirm.trim().isEmpty()
        || !password.trim().equals(passconfirm.trim())
        || firstName == null
        || firstName.trim().isEmpty()
        || lastName == null
        || lastName.trim().isEmpty()
        || country == null
        || country < 0) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  "Name, email, country, password or passconfirm is incorrect")
              .encodePrettily());
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        UsersVerticle.address + ".createPortalUser",
        body,
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotCreateNewUser")));
  }

  private void doCreateUser(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    String email = body.getString(Field.EMAIL.getName());
    if (email != null) {
      body.put(Field.EMAIL.getName(), email.toLowerCase());
    }
    String password = body.getString(Field.PASSWORD.getName());
    String passconfirm = body.getString("passconfirm");
    if (email == null
        || email.trim().isEmpty()
        || !email.matches(emailRegExp)
        || password == null
        || password.trim().isEmpty()
        || passconfirm == null
        || passconfirm.trim().isEmpty()
        || !password.trim().equals(passconfirm.trim())) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(Field.MESSAGE.getName(), "Login, email, password or passconfirm is incorrect")
              .encodePrettily());
    } else {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          UsersVerticle.address + ".createUser",
          body,
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotCreateNewUser")));
    }
  }

  private void doAdminCreateUser(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              AuthManager.address + ".adminCreateUser",
              body,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCreateNewUser")));
        });
  }

  private void doAdminGetMemcached(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject requestBody = Utils.getBodyAsJson(routingContext);

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              StatsVerticle.address + ".getMemcached",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>)
                  event -> simpleResponse(routingContext, event, "CouldNotGetMemcached"));
        });
  }

  private void doAdminDeleteMemcached(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              StatsVerticle.address + ".deleteMemcached",
              new JsonObject()
                  .put("memcachedKey", routingContext.request().getHeader("memcachedkey")),
              (Handler<AsyncResult<Message<JsonObject>>>)
                  event -> simpleResponse(routingContext, event, "CouldNotDeleteMemcached"));
        });
  }

  private void doNotifyAdmins(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        MailUtil.address + ".notifyAdmins",
        body.put("withHash", true),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotNotifyAdmin")));
  }

  private void doUpdateProfile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String userId = message.result().body().getString(Field.USER_ID.getName());
          JsonObject body = Utils.getBodyAsJson(routingContext);

          body.put(
              Field.SESSION_ID.getName(),
              message.result().body().getString(Field.SESSION_ID.getName()));
          body.put(
              Field.USERNAME.getName(),
              message.result().body().getString(Field.USERNAME.getName()));
          body.put(
              Field.GRAEBERT_ID.getName(),
              message.result().body().getString(Field.GRAEBERT_ID.getName()));
          body.put(
              Field.INTERCOM_ACCESS_TOKEN.getName(),
              message.result().body().getString(Field.INTERCOM_ACCESS_TOKEN.getName()));
          body.put(Field.USER_ID.getName(), userId);
          String currentPass = body.getString("currentPass");
          String name = body.getString(Field.NAME.getName());
          String surname = body.getString(Field.SURNAME.getName());
          String email = body.getString(Field.EMAIL.getName());
          if (email != null) {
            body.put(Field.EMAIL.getName(), email.toLowerCase());
          }
          String newPass = body.getString("newPass");
          String newPassConfirm = body.getString("newPassConfirm");
          JsonObject storage = body.getJsonObject("storage");
          JsonObject options = body.getJsonObject(Field.OPTIONS.getName());
          JsonObject preferences = body.getJsonObject(Field.PREFERENCES.getName());
          Boolean isTrialShown = body.getBoolean(Field.IS_TRIAL_SHOWN.getName());
          Long notificationBarShowed = body.getLong(Field.NOTIFICATION_BAR_SHOWED.getName());
          Boolean showRecent = body.getBoolean(Field.SHOW_RECENT.getName());
          String locale = body.getString(Field.LOCALE.getName());
          body.put("changeLocale", locale);
          String fileFilter = body.getString(Field.FILE_FILTER.getName());

          if (newPass != null && newPassConfirm != null) {
            if (currentPass == null || currentPass.trim().isEmpty()) {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(Field.MESSAGE.getName(), "Current password should be specified")
                      .encodePrettily());
              return;
            } else if (!newPass.trim().isEmpty()
                && !newPassConfirm.trim().isEmpty()
                && !newPass.equals(newPassConfirm)) {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(Field.MESSAGE.getName(), "Password mismatch")
                      .encodePrettily());
              return;
            }
          }

          if ((name == null || name.trim().isEmpty())
              && surname == null
              && (email == null || email.trim().isEmpty() || !email.matches(emailRegExp))
              && (newPass == null
                  || newPass.trim().isEmpty()
                  || newPassConfirm == null
                  || newPassConfirm.trim().isEmpty())
              && (storage == null || storage.isEmpty())
              && (options == null || options.isEmpty())
              && preferences == null
              && isTrialShown == null
              && showRecent == null
              && notificationBarShowed == null
              && (locale == null || locale.isEmpty())
              && (fileFilter == null || fileFilter.isEmpty())) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "Nothing to update")
                    .encodePrettily());
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".updateProfile",
                body,
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUpdateTheProfile")));
          }
        });
  }

  private void doDeleteUser(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());

    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          final String userId = isAdmin
              ? routingContext.request().getParam(Field.USER_ID.getName())
              : message.result().body().getString(Field.USER_ID.getName());
          if (AuthManager.adminId.equals(userId)) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_FORBIDDEN)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CouldNotDeleteAdmin"))
                    .encodePrettily());
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".deleteUser",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(
                        Field.TOKEN.getName(),
                        routingContext.request().getHeader(Field.TOKEN.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message11 -> {
                  if (message11.succeeded()
                      && message11
                          .result()
                          .body()
                          .getString(Field.STATUS.getName())
                          .equals(OK)) {
                    if (!isAdmin) {
                      String sessionId = getSessionCookie(routingContext);
                      eb_send_with_metrics(
                          optionalSegment.orElse(null),
                          routingContext,
                          AuthManager.address + ".logout",
                          new JsonObject().put(Field.SESSION_ID.getName(), sessionId));
                    }
                    int statusCode =
                        message11.result().body().getInteger(Field.STATUS_CODE.getName()) != null
                            ? message11.result().body().getInteger(Field.STATUS_CODE.getName())
                            : HttpStatus.SC_OK;
                    routingContext
                        .response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                    routingContext
                        .response()
                        .setStatusCode(statusCode)
                        .end(message11.result().body().encodePrettily());
                  } else {
                    simpleResponse(
                        routingContext,
                        message11,
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CouldNotDeleteUser"));
                  }
                });
          }
        });
  }

  private void doCognitoLogin(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".cognitoLogin",
        new JsonObject()
            .put("idToken", routingContext.request().getParam("id_token"))
            .put("ip", routingContext.request().remoteAddress().host())
            .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CognitoLoginFailed")));
  }

  private void doAuthenticate(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".digest",
        new JsonObject()
            .put(
                "authHeader",
                routingContext.request().getHeader(HttpHeaders.AUTHORIZATION.toString()))
            .put("ip", routingContext.request().remoteAddress().host())
            .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent")),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "DigestAuthFailed")));
  }

  private void doCheckAuth(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    optionalSegment.ifPresent(segment -> DynamoBusModBase.log.info(
        "$$$ " + System.currentTimeMillis() + " Router GET " + segment.getTraceId()));

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "CouldnNotAuth")));
  }

  private void doLogout(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String sessionId = getSessionCookie(routingContext);
    JsonObject json = new JsonObject().put(Field.SESSION_ID.getName(), sessionId);
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".logout",
        json,
        (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "CouldNotLogout")));
    // remove the key from the RateLimiterMap when logout requested
    RateLimitationHandler.removeRateLimiterForKey(RateLimitationHandler.getKey(routingContext));
  }

  private void doKillSession(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              AuthManager.address + ".killSession",
              new JsonObject()
                  .put(
                      Field.SESSION_ID.getName(),
                      routingContext.request().getParam(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotKillTheSession")));
        });
  }

  private void doGetUsersInfo(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());

    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          if (isAdmin) {
            // get specific user info
            if (routingContext.request().params().contains(Field.USER_ID.getName())) {
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  UsersVerticle.address + ".getInfo",
                  new JsonObject()
                      .put(
                          Field.USER_ID.getName(),
                          routingContext.request().params().get(Field.USER_ID.getName())),
                  (Handler<AsyncResult<Message<JsonObject>>>) message12 -> simpleResponse(
                      routingContext,
                      message12,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetListOfUsers")));
            } else {
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  UsersVerticle.address + ".getUsers",
                  new JsonObject()
                      .put(
                          Field.PAGE_TOKEN.getName(),
                          routingContext.request().getHeader(Field.PAGE_TOKEN.getName())),
                  (Handler<AsyncResult<Message<JsonObject>>>) message12 -> simpleResponse(
                      routingContext,
                      message12,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetListOfUsers")));
            }
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".getInfo",
                message.result().body(),
                // need to send only the required data (Artem's story)
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotGetUserInfo")));
          }
        });
  }

  private void doFindUser(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String pattern = routingContext.request().getHeader("pattern");
          if (!Utils.isStringNotNullOrEmpty(pattern)) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "Search pattern is required")
                    .encodePrettily());
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".findUser",
                new JsonObject().put("pattern", pattern),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                  if (message1.succeeded()
                      && message1
                          .result()
                          .body()
                          .getString(Field.STATUS.getName())
                          .equals(OK)) {
                    routingContext
                        .response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                    routingContext
                        .response()
                        .end(message1
                            .result()
                            .body()
                            .getJsonArray(Field.RESULTS.getName())
                            .encodePrettily());
                  } else {
                    simpleResponse(
                        routingContext,
                        message1,
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CouldNotFindUsers"));
                  }
                });
          }
        });
  }

  private void doGetMentionUsers(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    boolean isMention = (Boolean) routingContext.data().get("isMention");

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String pattern = routingContext.request().getHeader("pattern");
          pattern = Utils.convertIfUTF8Bytes(pattern);
          String userid = message.result().body().getString(Field.USER_ID.getName());
          String fileId = routingContext.request().getHeader(Field.FILE_ID.getName());
          boolean includeMyself =
              Boolean.parseBoolean(routingContext.request().headers().get("includeMyself"));
          String storage = null;
          if (Utils.isStringNotNullOrEmpty(fileId)) {
            JsonObject fileIdParsed = Utils.parseItemId(fileId);
            fileId = fileIdParsed.getString(Field.ID.getName());
            storage = fileIdParsed.getString(Field.STORAGE_TYPE.getName());
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FileIdMustBeSpecified"))
                    .encodePrettily());
          }
          if (!Utils.isStringNotNullOrEmpty(storage)) {
            String scope = routingContext.request().getHeader("scope");
            if (Utils.isStringNotNullOrEmpty(scope)) {
              storage = scope;
            }
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getUsersToMention",
              new JsonObject()
                  .put("pattern", pattern)
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.STORAGE_TYPE.getName(), storage)
                  .put(Field.USER_ID.getName(), userid)
                  .put("includeMyself", includeMyself)
                  .put("isMention", isMention),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetMentionedUsers")));
        });
  }

  private void doUpdateUsers(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          JsonObject options = body.getJsonObject(Field.OPTIONS.getName());
          if (options != null) {
            updateOptions(routingContext, options);
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "FL8"))
                    .encodePrettily());
          }
        });
  }

  private void doCreateSkeleton(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          try {
            JsonObject body = Utils.getBodyAsJson(routingContext);

            StorageType storageType = body.containsKey(Field.STORAGE_TYPE.getName())
                ? StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()))
                : StorageType.SAMPLES;
            String userId = routingContext.request().getParam(Field.USER_ID.getName());
            boolean force = body.containsKey(Field.FORCE.getName())
                ? body.getBoolean(Field.FORCE.getName())
                : false;
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".createSkeleton",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name())
                    .put(Field.FORCE.getName(), force),
                (Handler<AsyncResult<Message<JsonObject>>>) message2 -> simpleResponse(
                    routingContext,
                    message2,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotCreateSampleFiles")));
          } catch (java.lang.ClassCastException e) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "MalformedJsonRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doUpdateSkeleton(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          try {
            JsonObject body = Utils.getBodyAsJson(routingContext);

            StorageType storageType = body.containsKey(Field.STORAGE_TYPE.getName())
                ? StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()))
                : StorageType.SAMPLES;
            String userId = routingContext.request().getParam(Field.USER_ID.getName());
            boolean confirm = body.containsKey("confirm") ? body.getBoolean("confirm") : false;
            int version = body.containsKey("version") ? body.getInteger("version") : 0;
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                UsersVerticle.address + ".updateSkeleton",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name())
                    .put("confirm", confirm)
                    .put("version", version),
                (Handler<AsyncResult<Message<JsonObject>>>) message2 -> simpleResponse(
                    routingContext,
                    message2,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotCreateSampleFiles")));
          } catch (java.lang.ClassCastException e) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "MalformedJsonRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doUpdateUser(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          try {
            JsonObject body = Utils.getBodyAsJson(routingContext);

            Boolean enabled = body.getBoolean(Field.ENABLED.getName());
            JsonArray rolesAdd = body.getJsonArray("rolesAdd");
            JsonArray rolesRemove = body.getJsonArray("rolesRemove");
            JsonObject options = body.getJsonObject(Field.OPTIONS.getName());
            JsonObject disableThumbnailFilters = body.getJsonObject("disableThumbnailFilters");
            String compliance = body.getString("complianceStatus");
            if (enabled != null) {
              enableUser(routingContext, enabled);
            } else if (rolesAdd != null || rolesRemove != null) {
              changeRoles(routingContext, rolesAdd, rolesRemove);
            } else if (options != null) {
              updateOptions(routingContext, options);
            } else if (Utils.isStringNotNullOrEmpty(compliance)) {
              updateCompliance(routingContext, compliance);
            } else if (Utils.isJsonObjectNotNullOrEmpty(disableThumbnailFilters)) {
              updateThumbnailGeneration(optionalSegment.orElse(null), routingContext, body);
            } else {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(
                          Field.MESSAGE.getName(),
                          Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "FL8"))
                      .encodePrettily());
            }
          } catch (java.lang.ClassCastException e) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "MalformedJsonRequest"))
                    .encodePrettily());
          }
        });
  }

  private void updateCompliance(RoutingContext routingContext, final String compliance) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String userId = routingContext.request().getParam(Field.USER_ID.getName());
    if (userId != null) {
      if (!userId.equals(AuthManager.adminId)) {
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            UsersVerticle.address + ".updateCompliance",
            new JsonObject()
                .put(Field.ID.getName(), userId)
                .put("adminId", AuthManager.adminId)
                .put("complianceStatus", compliance),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
                routingContext,
                message,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotUpdateAccountStatus")));
      } else {
        routingContext
            .response()
            .setStatusCode(HttpStatus.SC_FORBIDDEN)
            .end(new JsonObject()
                .put(
                    Field.MESSAGE.getName(),
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "ChangesToAdminUserAreNotAllowed"))
                .encodePrettily());
      }
    } else {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "UseridMustBeSpecified"))
              .encodePrettily());
    }
  }

  private void enableUser(RoutingContext routingContext, final boolean enable) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String userId = routingContext.request().getParam(Field.USER_ID.getName());
    if (userId != null) {
      if (!userId.equals(AuthManager.adminId)) {
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            UsersVerticle.address + ".enableUser",
            new JsonObject().put(Field.ID.getName(), userId).put(Field.ENABLED.getName(), enable),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
                routingContext,
                message,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotUpdateAccountStatus")));
      } else {
        routingContext
            .response()
            .setStatusCode(HttpStatus.SC_FORBIDDEN)
            .end(new JsonObject()
                .put(
                    Field.MESSAGE.getName(),
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "ChangesToAdminUserAreNotAllowed"))
                .encodePrettily());
      }
    } else {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "UseridMustBeSpecified"))
              .encodePrettily());
    }
  }

  private void changeRoles(RoutingContext routingContext, JsonArray add, JsonArray remove) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String userId = routingContext.request().getParam(Field.USER_ID.getName());
    if (userId != null) {
      if (!userId.equals(AuthManager.adminId)) {
        JsonObject json = new JsonObject().put(Field.USER_ID.getName(), userId);
        if (add != null) {
          json.put("add", add);
        }
        if (remove != null) {
          json.put("remove", remove);
        }
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            UsersVerticle.address + ".changeRoles",
            json,
            (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
                routingContext,
                message,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotUpdateAccountRoles")));
      } else {
        routingContext
            .response()
            .setStatusCode(HttpStatus.SC_FORBIDDEN)
            .end(new JsonObject()
                .put(
                    Field.MESSAGE.getName(),
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "ChangesToAdminUserAreNotAllowed"))
                .encodePrettily());
      }
    } else {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "UseridMustBeSpecified"))
              .encodePrettily());
    }
  }

  private void updateOptions(RoutingContext routingContext, JsonObject options) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String userId = routingContext.request().getParam(Field.USER_ID.getName());
    if (userId != null) {
      if (!userId.equals(AuthManager.adminId)) {
        JsonObject json = new JsonObject().put(Field.USER_ID.getName(), userId);
        json.put(Field.OPTIONS.getName(), options);
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            UsersVerticle.address + ".updateProfile",
            json,
            (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
                routingContext,
                message,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotUpdateOptions")));
      } else {
        routingContext
            .response()
            .setStatusCode(HttpStatus.SC_FORBIDDEN)
            .end(new JsonObject()
                .put(
                    Field.MESSAGE.getName(),
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "ChangesToAdminUserAreNotAllowed"))
                .encodePrettily());
      }
    } else {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          UsersVerticle.address + ".updateAllProfiles",
          new JsonObject().put(Field.OPTIONS.getName(), options),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotUpdateOptions")));
    }
  }

  private void doCloneTmpl(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String tmplId = routingContext.request().getParam("tmplId");
          String userId = message.result().body().getString(Field.USER_ID.getName());
          JsonObject json = Utils.getBodyAsJson(routingContext);

          json.put("tmplId", tmplId)
              .put(Field.USER_ID.getName(), userId)
              .put(
                  Field.SESSION_ID.getName(),
                  message.result().body().getString(Field.SESSION_ID.getName()))
              .put(
                  Field.STORAGE_TYPE.getName(),
                  message.result().body().getString(Field.STORAGE_TYPE.getName()))
              .put(
                  Field.EXTERNAL_ID.getName(),
                  message.result().body().getString(Field.EXTERNAL_ID.getName()))
              .put(
                  Field.USERNAME.getName(),
                  message.result().body().getString(Field.USERNAME.getName()))
              .put(
                  Field.DEVICE.getName(), message.result().body().getString(Field.DEVICE.getName()))
              .put(
                  Field.PREFERENCES.getName(),
                  message.result().body().getJsonObject(Field.PREFERENCES.getName()))
              .put(
                  Field.IS_ADMIN.getName(),
                  message.result().body().getBoolean(Field.IS_ADMIN.getName()));
          if (json.getString(Field.FOLDER_ID.getName()) == null) {
            json.put(Field.FOLDER_ID.getName(), Field.MINUS_1.getName());
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".createByTmpl",
              json,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCreateFileUsingTemplate")));
        });
  }

  private void doCancelUpload(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject result = message.result().body();
          String uploadToken = routingContext.request().getHeader(Field.UPLOAD_TOKEN.getName());
          String presignedUploadId =
              routingContext.request().getHeader(Field.PRESIGNED_UPLOAD_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".cancelUpload",
              new JsonObject()
                  .put(Field.USER_ID.getName(), result.getString(Field.USER_ID.getName()))
                  .put(Field.STORAGE_TYPE.getName(), result.getString(Field.STORAGE_TYPE.getName()))
                  .put(Field.UPLOAD_TOKEN.getName(), uploadToken)
                  .put(Field.PRESIGNED_UPLOAD_ID.getName(), presignedUploadId),
              (Handler<AsyncResult<Message<JsonObject>>>) checkUploadMessage -> {
                simpleResponse(
                    routingContext,
                    checkUploadMessage,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotCancelUpload"));
              });
        });
  }

  private void doCheckUpload(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject result = message.result().body();
          String uploadToken = routingContext.request().getHeader(Field.UPLOAD_TOKEN.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".checkUpload",
              new JsonObject()
                  .put(Field.USER_ID.getName(), result.getString(Field.USER_ID.getName()))
                  .put(Field.STORAGE_TYPE.getName(), result.getString(Field.STORAGE_TYPE.getName()))
                  .put(Field.UPLOAD_TOKEN.getName(), uploadToken),
              (Handler<AsyncResult<Message<JsonObject>>>) checkUploadMessage -> {
                Message<JsonObject> uploadResult = checkUploadMessage.result();
                if (Objects.nonNull(uploadResult) && Objects.nonNull(uploadResult.body())) {
                  if (uploadResult.body().containsKey(Field.STATUS_CODE.getName())
                      && uploadResult
                          .body()
                          .getInteger(Field.STATUS_CODE.getName())
                          .equals(HttpStatus.SC_ACCEPTED)) {
                    // if upload is pending
                    routingContext
                        .response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                    routingContext
                        .response()
                        .setStatusCode(HttpStatus.SC_ACCEPTED)
                        .end(uploadResult.body().encodePrettily());
                    return;
                  }
                  handlePostUploadRequest(
                      optionalSegment.orElse(null),
                      uploadResult,
                      result.getString(Field.USER_ID.getName()),
                      result.getString(Field.SESSION_ID.getName()),
                      result.getString(Field.DEVICE.getName()),
                      StorageType.getStorageType(result.getString(Field.STORAGE_TYPE.getName())),
                      result.getString(Field.USERNAME.getName()),
                      true);
                }
                simpleResponse(
                    routingContext,
                    checkUploadMessage,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUploadFile"));
              });
        });
  }

  private void doUploadFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String externalId = message.result().body().getString(Field.EXTERNAL_ID.getName());
          String folderId = routingContext.request().getHeader(Field.FOLDER_ID.getName());
          if (folderId != null) {
            message.result().body().mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()));
            folderId = message.result().body().getString(Field.FOLDER_ID.getName());
          }
          final String userId = message.result().body().getString(Field.USER_ID.getName());
          boolean isAdmin = message.result().body().getBoolean(Field.IS_ADMIN.getName());
          final String device = message.result().body().getString(Field.DEVICE.getName());
          String sessionId = getSessionCookie(routingContext);
          String presignedUploadId =
              routingContext.request().getHeader(Field.PRESIGNED_UPLOAD_ID.getName());
          boolean isS3PresignedRequest = Utils.isStringNotNullOrEmpty(presignedUploadId);
          if ((routingContext.fileUploads() != null
                  && !routingContext.fileUploads().isEmpty())
              || isS3PresignedRequest) {
            int fileCount = 0;
            boolean isS3PresignedFileUploaded = false;
            while (fileCount < routingContext.fileUploads().size()
                || (isS3PresignedRequest && !isS3PresignedFileUploaded)) {
              FileBuffer buffer = new FileBuffer();
              String fileName;
              if (!isS3PresignedRequest) {
                FileUpload fileUpload = routingContext.fileUploads().get(fileCount);
                Buffer uploaded =
                    vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
                buffer.setData(uploaded.getBytes());
                if (routingContext.request().params().contains("fileName")) {
                  fileName = routingContext.request().params().get("fileName");
                } else {
                  fileName = fileUpload.fileName();
                }
              } else {
                Item presignedRequest =
                    S3PresignedUploadRequests.getRequestById(presignedUploadId, userId);
                if (Objects.nonNull(presignedRequest)) {
                  fileName = presignedRequest.getString(Field.FILE_NAME_C.getName());
                } else {
                  routingContext
                      .response()
                      .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                      .end(new JsonObject()
                          .put(
                              Field.MESSAGE.getName(),
                              Utils.getLocalizedString(
                                  RequestUtils.getLocale(routingContext),
                                  "ThereIsNoPresignedRequestInDB"))
                          .encodePrettily());
                  return;
                }
                buffer.setPresignedUploadId(presignedUploadId);
              }
              fileCount++;
              buffer.setFileName(fileName);
              buffer.setUserId(userId);
              buffer.setDevice(device);
              buffer.setLocale(RequestUtils.getLocale(routingContext));
              buffer.setAdmin(isAdmin);
              buffer.setSessionId(sessionId);
              buffer.setFolderId(folderId);
              if (routingContext.request().headers().contains("updateUsage")) {
                buffer.setUpdateUsage(
                    Boolean.parseBoolean(routingContext.request().getHeader("updateUsage")));
              }
              if (routingContext.request().headers().contains(Field.UPLOAD_REQUEST_ID.getName())) {
                buffer.setUploadRequestId(
                    routingContext.request().getHeader(Field.UPLOAD_REQUEST_ID.getName()));
              }
              // assuming it's file's update
              if (routingContext.request().getHeader(Field.FILE_ID.getName()) != null) {
                JsonObject itemId = Utils.parseItemId(
                    routingContext.request().getHeader(Field.FILE_ID.getName()),
                    Field.FILE_ID.getName());
                message.result().body().mergeIn(itemId);
                String xSessionId =
                    routingContext.request().headers().get(Field.X_SESSION_ID.getName()) != null
                        ? routingContext
                            .request()
                            .headers()
                            .get(Field.X_SESSION_ID.getName())
                            .trim()
                        : null;
                String fileId = itemId.getString(Field.FILE_ID.getName());
                buffer.setFileId(fileId);
                buffer.setXSessionId(xSessionId);

                JsonArray fileChangesInfo = null;
                if (routingContext.request().getFormAttribute("fileChangesInfo") != null) {
                  fileChangesInfo =
                      new JsonArray(routingContext.request().getFormAttribute("fileChangesInfo"));
                }

                JsonArray finalFileChangesInfo = fileChangesInfo;
                eb_request_with_metrics(
                    optionalSegment.orElse(null),
                    routingContext,
                    XSessionManager.address + ".update",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.SESSION_ID.getName(), sessionId)
                        .put(
                            Field.SESSION_STATE.getName(),
                            XenonSessions.SessionState.SAVE_PENDING.name())
                        .put(Field.DEVICE.getName(), device)
                        .put(Field.X_SESSION_ID.getName(), xSessionId)
                        .put(
                            Field.STORAGE_TYPE.getName(),
                            message.result().body().getString(Field.STORAGE_TYPE.getName()))
                        .put("changesAreSaved", false)
                        .put("fileChangesInfo", finalFileChangesInfo)
                        .put("editRequired", true),
                    (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                      JsonObject result = message1.result().body();
                      boolean fileSessionExpired = false, fileSavePending = false;
                      if (config.getCustomProperties().getNewSessionWorkflow()
                          && Utils.isStringNotNullOrEmpty(
                              result.getString(Field.ERROR_ID.getName()))
                          && !device.equalsIgnoreCase(AuthManager.ClientType.TOUCH.name())) {
                        fileSessionExpired = (result
                            .getString(Field.ERROR_ID.getName())
                            .equals("FileSessionHasExpired"));
                        fileSavePending =
                            (result.getString(Field.ERROR_ID.getName()).equals("FL19"));
                      }

                      if ((message1.succeeded()
                              && result.getString(Field.STATUS.getName()).equals(OK))
                          || fileSessionExpired
                          || fileSavePending) {
                        result.mergeIn(message.result().body());

                        if (fileSessionExpired || fileSavePending) {
                          buffer.setFileChangesInfo(finalFileChangesInfo);
                          if (result
                              .getJsonObject(Field.MESSAGE.getName())
                              .containsKey(Field.CONFLICTING_FILE_REASON.getName())) {
                            buffer.setConflictingFileReason(result
                                .getJsonObject(Field.MESSAGE.getName())
                                .getString(Field.CONFLICTING_FILE_REASON.getName()));
                          }
                          if (fileSessionExpired) {
                            buffer.setFileSessionExpired(true);
                          } else {
                            buffer.setFileSavePending(true);
                          }
                        }
                        if (!result.containsKey(Field.EXTERNAL_ID.getName())
                            || !Utils.isStringNotNullOrEmpty(
                                result.getString(Field.EXTERNAL_ID.getName()))) {
                          result.put(Field.EXTERNAL_ID.getName(), externalId);
                        }
                        uploadFile(optionalSegment.orElse(null), routingContext, result, buffer);
                      } else {
                        simpleResponse(
                            routingContext,
                            message1,
                            Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext), "CouldNotUploadFile"));
                      }
                    });
              } else {
                // handle copy comments for save-as
                MultiMap formAttributes = routingContext.request().formAttributes();
                if (formAttributes.contains(Field.CLONE_FILE_ID.getName())
                    && Utils.isStringNotNullOrEmpty(
                        formAttributes.get(Field.CLONE_FILE_ID.getName()))) {
                  buffer.setCloneFileId(formAttributes.get(Field.CLONE_FILE_ID.getName()));
                }
                if (formAttributes.contains(Field.COPY_COMMENTS.getName())
                    && Boolean.parseBoolean(formAttributes.get(Field.COPY_COMMENTS.getName()))) {
                  buffer.setCopyComments(true);
                }
                if (formAttributes.contains(Field.INCLUDE_RESOLVED_COMMENTS.getName())
                    && Boolean.parseBoolean(
                        formAttributes.get(Field.INCLUDE_RESOLVED_COMMENTS.getName()))) {
                  buffer.setIncludeResolvedComments(true);
                }
                if (formAttributes.contains(Field.INCLUDE_DELETED_COMMENTS.getName())
                    && Boolean.parseBoolean(
                        formAttributes.get(Field.INCLUDE_DELETED_COMMENTS.getName()))) {
                  buffer.setIncludeDeletedComments(true);
                }
                // hotfix for Touch/Commander + Xenon
                // (https://graebert.atlassian.net/browse/XENON-59737)
                if (!Utils.isStringNotNullOrEmpty(folderId)) {
                  buffer.setFolderId(Field.MINUS_1.getName());
                }
                uploadFile(
                    optionalSegment.orElse(null),
                    routingContext,
                    message.result().body(),
                    buffer);
              }
              if (isS3PresignedRequest) {
                isS3PresignedFileUploaded = true;
              }
            }
          } else {

            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "ThereIsNoFileInTheRequest"))
                    .encodePrettily());
          }
        });
  }

  private void uploadFile(
      Entity segment, RoutingContext routingContext, JsonObject result, FileBuffer buffer) {
    StorageType storageType =
        StorageType.getStorageType(result.getString(Field.STORAGE_TYPE.getName()));
    String username = result.getString(Field.USERNAME.getName());
    buffer.setUserName(username);
    buffer.setFname(result.getString(Field.F_NAME.getName()));
    buffer.setSurname(result.getString(Field.SURNAME.getName()));
    buffer.setEmail(result.getString(Field.EMAIL.getName()));
    buffer.setExternalId(result.getString(Field.EXTERNAL_ID.getName()));
    buffer.setPreferences(result.getJsonObject(Field.PREFERENCES.getName()));
    buffer.setStorageType(storageType.name());
    buffer.setBaseChangeId(
        (buffer.isFileSessionExpired() || buffer.isFileSavePending())
            ? Utils.generateUUID()
            : routingContext.request().headers().get(Field.BASE_CHANGE_ID.getName()));

    boolean isFileUpdate = Objects.nonNull(buffer.getFileId());

    XRayEntityUtils.putAnnotation(segment, XrayField.FROM_XENON, isFileUpdate);
    XRayEntityUtils.putAnnotation(segment, XrayField.USER_ID, buffer.getUserId());
    XRayEntityUtils.putAnnotation(segment, XrayField.SESSION_ID, buffer.getSessionId());
    XRayEntityUtils.putMetadata(segment, XrayField.FILE_ID, buffer.getFileId());
    if (isFileUpdate) {
      XRayEntityUtils.putAnnotation(segment, XrayField.X_SESSION_ID, buffer.getXSessionId());
    }
    eb_request(
        segment,
        GridFSModule.address + ".uploadFile",
        MessageUtils.generateBuffer(segment, buffer),
        (Handler<AsyncResult<Message<JsonObject>>>) uploadResponseMessage -> {
          Message<JsonObject> uploadResult = uploadResponseMessage.result();
          if (Objects.nonNull(uploadResult) && Objects.nonNull(uploadResult.body())) {
            // Return uploadToken and check the upload status periodically
            if (uploadResult.body().getString(Field.STATUS.getName()).equals(OK)
                && uploadResult.body().containsKey(Field.UPLOAD_TOKEN.getName())) {
              routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_OK)
                  .end(uploadResult.body().encodePrettily());
              return;
            }

            if (routingContext.request().getHeader(Field.FILE_ID.getName()) != null
                && uploadResult.body().containsKey(Field.VERSION_ID.getName())) {
              routingContext
                  .response()
                  .putHeader(
                      Field.VERSION_ID.getName(),
                      uploadResult.body().getString(Field.VERSION_ID.getName()));
            }

            handlePostUploadRequest(
                segment,
                uploadResult,
                buffer.getUserId(),
                buffer.getSessionId(),
                buffer.getDevice(),
                storageType,
                username,
                routingContext.request().getHeader(Field.FILE_ID.getName()) == null);
          } else {
            XRayEntityUtils.putAnnotation(segment, XrayField.IS_CONFLICTED, false);
          }
          simpleResponse(
              routingContext,
              uploadResponseMessage,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotUploadFile"));
        });
  }

  private void handlePostUploadRequest(
      Entity segment,
      Message<JsonObject> uploadResult,
      String userId,
      String sessionId,
      String device,
      StorageType storageType,
      String username,
      boolean isNewFile) {
    if (uploadResult.body().getBoolean(Field.IS_CONFLICTED.getName(), false)) {
      XRayEntityUtils.putAnnotation(segment, XrayField.IS_CONFLICTED, true);
      XRayEntityUtils.putAnnotation(
          segment,
          XrayField.CONFLICTED_FILE_REASON,
          uploadResult.body().getString(Field.CONFLICTING_FILE_REASON.getName()));
      XRayEntityUtils.putMetadata(
          segment,
          XrayField.CONFLICTED_FILE_ID,
          uploadResult.body().getString(Field.FILE_ID.getName()));
      XRayEntityUtils.putMetadata(
          segment, XrayField.VERSION_ID, uploadResult.body().getString(Field.VERSION_ID.getName()));
    } else {
      XRayEntityUtils.putAnnotation(segment, XrayField.IS_CONFLICTED, false);
    }

    if (isNewFile && uploadResult.body().getString(Field.STATUS.getName()).equals(OK)) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, null)
          .withName("saveFileLog")
          .runWithoutSegment(() -> {
            FileLog fileLog = new FileLog(
                userId,
                uploadResult.body().getString(Field.FILE_ID.getName()),
                storageType,
                username,
                GMTHelper.utcCurrentTime(),
                true,
                FileActions.UPLOAD,
                GMTHelper.utcCurrentTime(),
                null,
                sessionId,
                null,
                device,
                null);
            fileLog.sendToServer();
          });
    }
  }

  private void doGetFiles(RoutingContext routingContext) {
    // if userId is specified check if current is admin and return list of user's folder files,
    // otherwise return files of folder of current user
    final String ownerId = routingContext.request().headers().get(Field.USER_ID.getName());
    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());
    boolean trash = (Boolean) routingContext.data().get(Field.TRASH.getName());

    if (isAdmin && ownerId == null) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(Field.MESSAGE.getName(), "userId must be specified")
              .encodePrettily());
      return;
    }
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          // this happens when a client sends /api/folders//path (so empty path)
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          if (Field.PATH.getName().equals(folderId)) {
            simpleResponse(
                routingContext,
                message,
                Utils.getLocalizedString(
                    RequestUtils.getLocale(routingContext), "CouldNotGetFolderContent"));
            return;
          }
          JsonObject options = message.result().body().getJsonObject(Field.OPTIONS.getName());

          if (isAdmin) {
            getFolderContent(
                optionalSegment.orElse(null),
                routingContext,
                false,
                new JsonObject()
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(Field.OWNER_ID.getName(), ownerId)
                    .put(
                        Field.DEVICE.getName(),
                        message.result().body().getString(Field.DEVICE.getName()))
                    .put(
                        "isUserThumbnailDisabled",
                        options.containsKey("disableThumbnail")
                            ? options.getBoolean("disableThumbnail")
                            : false)
                    .put(
                        Field.IS_ADMIN.getName(),
                        message.result().body().getBoolean(Field.IS_ADMIN.getName())));
          } else {
            String storageType = routingContext.request().getHeader(Field.STORAGE_TYPE.getName());
            String externalId = routingContext.request().getHeader(Field.EXTERNAL_ID.getName());
            // get content of any folder of any storage
            if (Utils.isStringNotNullOrEmpty(storageType)
                && Utils.isStringNotNullOrEmpty(externalId)) {
              message
                  .result()
                  .body()
                  .put(Field.STORAGE_TYPE.getName(), storageType)
                  .put(Field.EXTERNAL_ID.getName(), externalId);
            }
            String fileFilter = routingContext.request().getHeader(Field.FILE_FILTER.getName());
            if (fileFilter == null || fileFilter.trim().isEmpty()) {
              fileFilter = options.getString(Field.FILE_FILTER.getName());
            }
            Boolean no_debug_log = options.getBoolean(Field.NO_DEBUG_LOG.getName());
            getFolderContent(
                optionalSegment.orElse(null),
                routingContext,
                trash,
                new JsonObject()
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(
                        Field.OWNER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.IS_ADMIN.getName(),
                        message.result().body().getBoolean(Field.IS_ADMIN.getName()))
                    .put(Field.FILE_FILTER.getName(), fileFilter)
                    .put(Field.NO_DEBUG_LOG.getName(), no_debug_log)
                    .put(
                        Field.DEVICE.getName(),
                        message.result().body().getString(Field.DEVICE.getName()))
                    .put(
                        "isUserThumbnailDisabled",
                        options.containsKey("disableThumbnail")
                            ? options.getBoolean("disableThumbnail")
                            : false)
                    .put(
                        Field.PREFERENCES.getName(),
                        message.result().body().getJsonObject(Field.PREFERENCES.getName())));
          }
        });
  }

  private void doEraseAll(RoutingContext routingContext) {
    final String userId = routingContext.request().headers().get(Field.USER_ID.getName());
    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());
    if (isAdmin && userId == null) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(Field.MESSAGE.getName(), "userId must be specified")
              .encodePrettily());
      return;
    }
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject fields = Utils.parseObjectFields(routingContext, message);
          String storageType = fields.getString(Field.STORAGE_TYPE.getName());
          String externalId = fields.getString(Field.EXTERNAL_ID.getName());

          if (storageType == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "StorageType is not specified")
                    .encodePrettily());
            return;
          }

          if (externalId == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "ExternalId is not specified")
                    .encodePrettily());
            return;
          }

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".eraseAll",
              new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType)
                  .put(Field.EXTERNAL_ID.getName(), externalId)
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotGetFolderContent"));
              });
        });
  }

  private void doGetRecentFiles(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              RecentFilesVerticle.address + ".getRecentFiles",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetRecentFiles")));
        });
  }

  private void doDeleteRecentFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String userId = message.result().body().getString(Field.USER_ID.getName());
          // parsing encapsulated fileId
          JsonObject parsedFileId = Utils.parseItemId(fileId, Field.FILE_ID.getName());

          JsonObject deleteRequest = new JsonObject();
          deleteRequest.put(Field.USER_ID.getName(), userId);
          deleteRequest.put(
              Field.FILE_ID.getName(), parsedFileId.getString(Field.FILE_ID.getName()));
          deleteRequest.put(
              Field.STORAGE_TYPE.getName(), parsedFileId.getString(Field.STORAGE_TYPE.getName()));

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              RecentFilesVerticle.address + ".deleteRecentFile",
              deleteRequest,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteRecentFile")));
        });
  }

  private void doRestoreRecentFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject requestBody = Utils.getBodyAsJson(routingContext);

          String userId = message.result().body().getString(Field.USER_ID.getName());
          String fileId = requestBody.getString(Field.FILE_ID.getName());

          // parsing encapsulated fileId
          JsonObject parsedFileId = Utils.parseItemId(fileId, Field.FILE_ID.getName());

          requestBody.put(Field.USER_ID.getName(), userId);
          requestBody.put(Field.FILE_ID.getName(), parsedFileId.getString(Field.FILE_ID.getName()));
          requestBody.put(
              Field.EXTERNAL_ID.getName(), parsedFileId.getString(Field.EXTERNAL_ID.getName()));
          requestBody.put(
              Field.STORAGE_TYPE.getName(), parsedFileId.getString(Field.STORAGE_TYPE.getName()));

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              RecentFilesVerticle.address + ".restoreRecentFile",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotRestoreRecentFile")));
        });
  }

  private void doValidateRecentFiles(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              RecentFilesVerticle.address + ".validateRecentFiles",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetRecentFiles")));
        });
  }

  private void doValidateSingleRecentFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject validationRequest = message.result().body();
          JsonObject parsedFileId = Utils.parseItemId(fileId, Field.FILE_ID.getName());
          validationRequest.put(
              Field.FILE_ID.getName(), parsedFileId.getString(Field.FILE_ID.getName()));
          validationRequest.put(
              Field.EXTERNAL_ID.getName(), parsedFileId.getString(Field.EXTERNAL_ID.getName()));
          validationRequest.put(
              Field.STORAGE_TYPE.getName(), parsedFileId.getString(Field.STORAGE_TYPE.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              RecentFilesVerticle.address + ".validateSingleRecentFile",
              validationRequest,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetRecentFiles")));
        });
  }

  private void doSearch(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }

          String query = routingContext.request().getHeader(Field.QUERY.getName());
          try {
            query = URLDecoder.decode(query, StandardCharsets.UTF_8);
          } catch (Exception ignore) {
          }
          String storageType = routingContext.request().getHeader(Field.STORAGE_TYPE.getName());
          if (storageType == null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".search",
                message.result().body().put("pattern", query),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNoFindObjects")));
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".globalSearch",
                message
                    .result()
                    .body()
                    .put(Field.STORAGE_TYPE.getName(), storageType)
                    .put(Field.QUERY.getName(), query)
                    .put(
                        Field.IS_ADMIN.getName(),
                        message.result().body().getBoolean(Field.IS_ADMIN.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message12 -> simpleResponse(
                    routingContext,
                    message12,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNoFindObjects")));
          }
        });
  }

  private void getFolderContent(
      Entity segment, RoutingContext routingContext, boolean trash, JsonObject json) {
    String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
    if (folderId == null) {
      folderId = Field.MINUS_1.getName();
    }

    String pageToken = routingContext.request().getHeader(Field.PAGE_TOKEN.getName());
    String useNewStructure = routingContext.request().getHeader(Field.USE_NEW_STRUCTURE.getName());
    String full = routingContext.request().getHeader(Field.FULL.getName());
    String dbTrash = routingContext.request().getHeader(Field.TRASH.getName());
    String type = routingContext.request().getHeader(Field.TYPE.getName());
    eb_request_with_metrics(
        segment,
        routingContext,
        GridFSModule.address + ".getFolderContent",
        json.put(Field.FOLDER_ID.getName(), folderId)
            .put(Field.TRASH.getName(), trash)
            .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
            .put(Field.PAGE_TOKEN.getName(), pageToken)
            .put(Field.FULL.getName(), full == null ? null : Boolean.parseBoolean(full))
            .put(
                Field.SESSION_ID.getName(),
                routingContext.request().getHeader(Field.SESSION_ID.getName()))
            .put(
                Field.PAGINATION.getName(),
                routingContext.request().getHeader(Field.PAGINATION.getName()))
            .put("dbTrash", Boolean.parseBoolean(dbTrash))
            .put(Field.USE_NEW_STRUCTURE.getName(), Boolean.parseBoolean(useNewStructure))
            .put(Field.DEVICE.getName(), json.getString(Field.DEVICE.getName()))
            .put(Field.TYPE.getName(), type),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotGetFolderContent"));
        });
  }

  private void doGetFileData(RoutingContext routingContext) {
    doGetFileData(routingContext, false);
  }

  private void doGetFileData(RoutingContext routingContext, boolean download) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    String token = routingContext.request().getHeader(Field.TOKEN.getName());
    String downloadToken = routingContext.request().getHeader(Field.DOWNLOAD_TOKEN.getName());
    String encrypted = routingContext.request().getHeader("encrypted");
    String locale = RequestUtils.getLocale(routingContext);
    JsonObject jsonId = Utils.parseItemId(
        routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName());
    String mode = jsonId.getString("encapsulationMode");
    String range = routingContext.request().getHeader("Range");
    Integer start = null, end = null;
    if (range != null && range.matches("^bytes=\\d*-\\d*(,\\d*-\\d*)*$")) {
      for (String part : range.substring(6).split(",")) {
        String substring = part.substring(0, part.indexOf("-"));
        start = (!substring.isEmpty()) ? Integer.parseInt(substring) : null;
        substring = part.substring(part.indexOf("-") + 1);
        end = (!substring.isEmpty()) ? Integer.parseInt(substring) : null;
      }
    }
    if (downloadToken != null) {
      String sessionId = routingContext.request().getHeader("sessionid");
      if (sessionId != null) {
        if (!new AuthProvider().authenticate(routingContext, sessionId)) {
          return;
        }
        if (!isUserAdminAndIsStorageAvailable(routingContext)) {
          return;
        }
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            AuthManager.address + ".additionalAuth",
            AuthManager.getAuthData(routingContext),
            (Handler<AsyncResult<Message<JsonObject>>>) authMessage -> {
              if (!isAuthSuccessful(routingContext, authMessage)) {
                return;
              }
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  GridFSModule.address + ".getDownload",
                  new JsonObject()
                      .put(Field.TOKEN.getName(), downloadToken)
                      .put(
                          Field.USER_ID.getName(),
                          authMessage.result().body().getString(Field.USER_ID.getName()))
                      .mergeIn(jsonId),
                  message1 -> handleGetFileResponse(
                      optionalSegment.orElse(null),
                      message1,
                      authMessage.result().body(),
                      routingContext,
                      "CannotDownloadFile",
                      downloadToken,
                      null,
                      download));
            });
      } else {
        routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
      }
    } else if (mode != null && mode.equals("0")) {
      String baseChangeId = routingContext.request().getHeader(Field.BASE_CHANGE_ID.getName());
      JsonObject json = new JsonObject()
          .put(Field.USER_ID.getName(), "BOX_USER") // just in case
          .put(Field.IS_ADMIN.getName(), false)
          .put(Field.STORAGE_TYPE.getName(), jsonId.getString(Field.STORAGE_TYPE.getName()))
          .put(Field.EXTERNAL_ID.getName(), jsonId.getString(Field.EXTERNAL_ID.getName()))
          .put(Field.BASE_CHANGE_ID.getName(), baseChangeId)
          .put(
              Field.FOLDER_ID.getName(),
              routingContext.request().getHeader(Field.FOLDER_ID.getName()))
          .put("encapsulationMode", mode)
          .put(
              Field.X_SESSION_ID.getName(),
              routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
          .put(Field.LOCALE.getName(), locale)
          .mergeIn(jsonId);
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".getFile",
          json,
          message1 -> handleGetFileResponse(
              optionalSegment.orElse(null),
              message1,
              null,
              routingContext,
              "CouldNotGetFile",
              null,
              null,
              download));
    } else if (token == null || token.isEmpty()) {
      String sessionId = getSessionCookie(routingContext);
      if (sessionId != null) {
        if (!new AuthProvider().authenticate(routingContext, sessionId)) {
          return;
        }
        if (!isUserAdminAndIsStorageAvailable(routingContext)) {
          return;
        }
        Integer finalStart = start;
        Integer finalEnd = end;
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            AuthManager.address + ".additionalAuth",
            AuthManager.getAuthData(routingContext),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> {
              if (!isAuthSuccessful(routingContext, message)) {
                return;
              }
              message.result().body().mergeIn(jsonId);
              JsonObject json = new JsonObject()
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      message.result().body().getString(Field.EXTERNAL_ID.getName()))
                  .put(
                      Field.BASE_CHANGE_ID.getName(),
                      routingContext.request().getHeader(Field.BASE_CHANGE_ID.getName()))
                  .put(
                      Field.IS_ADMIN.getName(),
                      message.result().body().getBoolean(Field.IS_ADMIN.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.FOLDER_ID.getName(),
                      routingContext.request().getHeader(Field.FOLDER_ID.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.USERNAME.getName(),
                      message.result().body().getString(Field.USERNAME.getName()))
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      message.result().body().getString(Field.STORAGE_TYPE.getName()))
                  .put(Field.LOCALE.getName(), locale)
                  .put(
                      Field.VER_ID.getName(),
                      routingContext.request().headers().get(Field.VERSION_ID.getName()))
                  .put(
                      Field.X_SESSION_ID.getName(),
                      routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
                  .put(Field.START.getName(), finalStart)
                  .put(Field.END.getName(), finalEnd)
                  .mergeIn(jsonId);
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  GridFSModule.address + ".getFile",
                  json,
                  message1 -> handleGetFileResponse(
                      optionalSegment.orElse(null),
                      message1,
                      message.result().body(),
                      routingContext,
                      "CouldNotGetFile",
                      null,
                      finalStart,
                      download));
            });
      } else {
        routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
      }
    } else {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".getFileByToken",
          new JsonObject()
              .put(Field.LOCALE.getName(), locale)
              .put(Field.TOKEN.getName(), token)
              .put("encrypted", encrypted)
              .put(
                  Field.PASSWORD.getName(),
                  routingContext.request().headers().get(Field.PASSWORD.getName()))
              .put(
                  Field.X_SESSION_ID.getName(),
                  routingContext.request().headers().get(Field.X_SESSION_ID.getName()))
              .mergeIn(jsonId),
          message -> {
            new ExecutorServiceAsyncRunner(
                    executorService, operationGroup, optionalSegment.orElse(null), null)
                .withName("addSubscription")
                .run((Segment blockingSegment) -> {
                  String sessionId = getSessionCookie(routingContext);
                  if (sessionId != null) {
                    if (!new AuthProvider().authenticate(routingContext, sessionId, false)) {
                      return;
                    }
                    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
                      return;
                    }
                    eb_request_with_metrics(
                        blockingSegment,
                        routingContext,
                        AuthManager.address + ".additionalAuth",
                        AuthManager.getAuthData(routingContext),
                        (Handler<AsyncResult<Message<JsonObject>>>) authMessage -> {
                          if (!isAuthSuccessful(routingContext, authMessage)) {
                            return;
                          }
                          // try to add subscription if user
                          // is authenticated
                          String userId =
                              authMessage.result().body().getString(Field.USER_ID.getName());
                          String fileId = jsonId.getString(Field.FILE_ID.getName());
                          String storageType = jsonId.getString(Field.STORAGE_TYPE.getName());
                          eb_send(
                              blockingSegment,
                              Subscriptions.address + ".addSubscription",
                              new JsonObject()
                                  .put(Field.FILE_ID.getName(), fileId)
                                  .put(Field.USER_ID.getName(), userId)
                                  .put(Field.STORAGE_TYPE.getName(), storageType)
                                  .put(
                                      "scope",
                                      new JsonArray()
                                          .add(
                                              Subscriptions.subscriptionScope.MODIFICATIONS
                                                  .toString()))
                                  .put("scopeUpdate", Subscriptions.scopeUpdate.APPEND.toString())
                                  .put(Field.TOKEN.getName(), token));
                        });
                  }
                });
            handleGetFileResponse(
                optionalSegment.orElse(null),
                message,
                null,
                routingContext,
                "CouldNotGetFileByToken",
                null,
                null,
                download);
          });
    }
  }

  private void handleGetFileResponse(
      Entity parentSegment,
      AsyncResult<Message<Object>> message,
      JsonObject authMessageObject,
      RoutingContext routingContext,
      String errorId,
      String downloadToken,
      Integer start,
      boolean download) {
    if (!message.succeeded()
        || message.result().body() instanceof JsonObject
            && ((JsonObject) message.result().body())
                .getString(Field.STATUS.getName())
                .equals(ERROR)) {
      simpleResponse(
          message,
          routingContext,
          Utils.getLocalizedString(RequestUtils.getLocale(routingContext), errorId));
    } else {
      JsonObject body = null;
      try {
        if (message.result().body() instanceof JsonObject) {
          body = (JsonObject) message.result().body();
        }
      } catch (Exception ignore) {
      }
      if (downloadToken == null
          || body != null
              && (ERROR.equals(body.getString(Field.STATUS.getName()))
                  || Integer.valueOf(HttpStatus.SC_ACCEPTED)
                      .equals(body.getInteger(Field.STATUS_CODE.getName())))) {
        if (body != null) {
          if (body.containsKey(Field.DOWNLOAD_URL.getName())) {
            StreamHelper.downloadToStream(
                routingContext, BaseVerticle.HTTP_CLIENT, body, errorId, HttpStatus.SC_OK);
            return;
          }
          routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
          int statusCode = body.getInteger(Field.STATUS_CODE.getName()) != null
              ? body.getInteger(Field.STATUS_CODE.getName())
              : HttpStatus.SC_INTERNAL_SERVER_ERROR;
          routingContext.response().setStatusCode(statusCode).end(body.encodePrettily());
          return;
        }
      }

      ParsedMessage parsedMessage = MessageUtils.parse(message.result());

      if (download) {
        routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/force-download");
        routingContext.response().putHeader(HttpHeaders.CONTENT_TRANSFER_ENCODING, "binary");
        routingContext
            .response()
            .putHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\""
                    + parsedMessage.getJsonObject().getString(Field.NAME.getName()) + "\"");
        routingContext
            .response()
            .putHeader(
                HttpHeaders.CONTENT_LENGTH,
                Long.toString(parsedMessage.getContentAsByteArray().length));
      } else if (start != null && start >= 0) {
        long length = parsedMessage.getJsonObject().getLong("length");
        routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
        routingContext
            .response()
            .putHeader(
                HttpHeaders.CONTENT_LENGTH,
                Long.toString(parsedMessage.getContentAsByteArray().length));
        routingContext
            .response()
            .putHeader(
                HttpHeaders.CONTENT_RANGE,
                "bytes " + start + "-" + (start + parsedMessage.getContentAsByteArray().length - 1)
                    + "/" + length);
        routingContext.response().setStatusCode(HttpStatus.SC_PARTIAL_CONTENT);
      } else {
        routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
        routingContext
            .response()
            .putHeader(
                HttpHeaders.CONTENT_LENGTH,
                Integer.toString(parsedMessage.getContentAsByteArray().length));
        if (parsedMessage.getJsonObject().getString(Field.VER_ID.getName()) != null) {
          routingContext
              .response()
              .putHeader(
                  Field.VERSION_ID.getName(),
                  parsedMessage.getJsonObject().getString(Field.VER_ID.getName()));
        }
        // recent files
        String name = parsedMessage.getJsonObject().getString(Field.NAME.getName());
        boolean open = Boolean.parseBoolean(routingContext.request().getHeader("open"));
        if (open && name != null && name.toLowerCase().endsWith(".pdf")) {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, parentSegment, null)
              .withName("saveRecentFile")
              .run((Segment blockingSegment) -> {
                eb_send(
                    blockingSegment,
                    RecentFilesVerticle.address + ".saveRecentFile",
                    authMessageObject.mergeIn(Utils.parseItemId(
                        routingContext.request().getParam(Field.FILE_ID.getName()),
                        Field.FILE_ID.getName())));
              });
        }
      }
      routingContext.response().write(Buffer.buffer(parsedMessage.getContentAsByteArray()));
      routingContext.response().end();
    }
  }

  private void doGetVersions(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getVersions",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetVersionsOfFile")));
        });
  }

  private void getLatestVersionId(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getLatestVersionId",
              message.result().body(),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetVersionsOfFile")));
        });
  }

  private void doGetVersionData(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    final String token = routingContext.request().getParam(Field.TOKEN.getName());
    final String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
    final String versionId = routingContext.request().getParam(Field.VERSION_ID.getName());
    final String folderId = routingContext.request().getHeader(Field.FOLDER_ID.getName());
    final String format = routingContext.request().getHeader("format");
    String downloadToken = routingContext.request().getHeader(Field.DOWNLOAD_TOKEN.getName());
    final String locale = RequestUtils.getLocale(routingContext);

    Handler<AsyncResult<Message<Object>>> replyHandler = getVersionMessage -> {
      if (!getVersionMessage.succeeded()
          || getVersionMessage.result().body() instanceof JsonObject) {
        JsonObject body = null;
        try {
          body = (JsonObject) getVersionMessage.result().body();
        } catch (Exception ignore) {
        }
        if (Objects.nonNull(body) && body.containsKey(Field.DOWNLOAD_URL.getName())) {
          StreamHelper.downloadToStream(
              routingContext, BaseVerticle.HTTP_CLIENT, body, "CouldNotGetFile", HttpStatus.SC_OK);
          return;
        }
        simpleResponse(
            getVersionMessage, routingContext, Utils.getLocalizedString(locale, "CouldNotGetFile"));
      } else {
        ParsedMessage parsedMessage = MessageUtils.parse(getVersionMessage.result());

        if (parsedMessage.getJsonObject().getBoolean("downloadLink", false)) {
          String name = parsedMessage.getJsonObject().getString(Field.NAME.getName());
          routingContext
              .response()
              .putHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=".concat(name));
        }

        routingContext.response().putHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
        routingContext
            .response()
            .putHeader(
                HttpHeaders.CONTENT_LENGTH,
                Integer.toString(parsedMessage.getContentAsByteArray().length));
        routingContext.response().write(Buffer.buffer(parsedMessage.getContentAsByteArray()));
        routingContext.response().end();
      }
    };

    // token get version request
    if (Utils.isStringNotNullOrEmpty(token)) {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".getVersionByToken",
          new JsonObject()
              .put(Field.LOCALE.getName(), locale)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.TOKEN.getName(), token)
              .put("format", format)
              .put(Field.DOWNLOAD_TOKEN.getName(), downloadToken)
              .put(
                  Field.PASSWORD.getName(),
                  routingContext.request().headers().get(Field.PASSWORD.getName())),
          replyHandler);
    }
    // regular get version request
    else {
      if (!new AuthProvider().authenticate(routingContext, getSessionCookie(routingContext))) {
        return;
      }

      if (!isUserAdminAndIsStorageAvailable(routingContext)) {
        return;
      }

      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          AuthManager.address + ".additionalAuth",
          AuthManager.getAuthData(routingContext),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> {
            if (!isAuthSuccessful(routingContext, message)) {
              return;
            }
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".getVersion",
                new JsonObject()
                    .put(Field.LOCALE.getName(), locale)
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(Field.VER_ID.getName(), versionId)
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(Field.FOLDER_ID.getName(), folderId)
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(Field.FILE_ID.getName(), fileId),
                replyHandler);
          });
    }
  }

  private void doDeleteData(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject fileId = Utils.parseItemId(
              routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName());
          String versionId = routingContext.request().getParam(Field.VERSION_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".deleteVersion",
              message
                  .result()
                  .body()
                  .mergeIn(fileId)
                  .put(Field.VER_ID.getName(), versionId)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteFile")));
        });
  }

  private void doUntrashFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".untrash",
              message
                  .result()
                  .body()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotRestoreFile")));
        });
  }

  private CompositeFuture renameObjects(
      Entity parentSegment,
      RoutingContext routingContext,
      AsyncResult<Message<JsonObject>> message,
      JsonObject objects) {
    Entity segment =
        XRayManager.createStandaloneSegment(operationGroup, parentSegment, "renameObjects");
    JsonArray files = !objects.containsKey(Field.FILES.getName())
        ? new JsonArray()
        : objects.getJsonArray(Field.FILES.getName());
    JsonArray folders = !objects.containsKey(Field.FOLDERS.getName())
        ? new JsonArray()
        : objects.getJsonArray(Field.FOLDERS.getName());
    List<Future<Boolean>> queue = new ArrayList<>();
    files.forEach(fileObj -> {
      JsonObject file = (JsonObject) fileObj;
      if (file.containsKey(Field.NAME.getName())) {
        Promise<Boolean> handler = Promise.promise();
        queue.add(handler.future());
        JsonObject data = new JsonObject()
            .put("ignoreDeleted", true)
            .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
            .put(Field.ID.getName(), file.getString(Field.ID.getName()))
            .put(Field.NAME.getName(), file.getString(Field.NAME.getName()))
            .put(
                Field.OWNER_ID.getName(),
                message.result().body().getString(Field.USER_ID.getName()))
            .put(
                Field.STORAGE_TYPE.getName(),
                message.result().body().getString(Field.STORAGE_TYPE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(),
                message.result().body().getString(Field.EXTERNAL_ID.getName()))
            .put(
                Field.SESSION_ID.getName(),
                message.result().body().getString(Field.SESSION_ID.getName()));
        eb_request_with_metrics(
            segment, routingContext, GridFSModule.address + ".renameFile", data, (result) -> {
              handler.complete(result.succeeded());
            });
      }
    });
    folders.forEach(folderObj -> {
      JsonObject folder = (JsonObject) folderObj;
      Promise<Boolean> handler = Promise.promise();
      queue.add(handler.future());
      if (folder.containsKey(Field.NAME.getName())) {
        JsonObject data = new JsonObject()
            .put("ignoreDeleted", true)
            .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
            .put(Field.ID.getName(), folder.getString(Field.ID.getName()))
            .put(Field.NAME.getName(), folder.getString(Field.NAME.getName()))
            .put(
                Field.OWNER_ID.getName(),
                message.result().body().getString(Field.USER_ID.getName()))
            .put(
                Field.STORAGE_TYPE.getName(),
                message.result().body().getString(Field.STORAGE_TYPE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(),
                message.result().body().getString(Field.EXTERNAL_ID.getName()))
            .put(
                Field.SESSION_ID.getName(),
                message.result().body().getString(Field.SESSION_ID.getName()));
        eb_request_with_metrics(
            segment, routingContext, GridFSModule.address + ".renameFolder", data, (result) -> {
              handler.complete(result.succeeded());
            });
      } else {
        handler.complete(true);
      }
    });
    return TypedCompositeFuture.join(queue);
  }

  private void doRestoreBatch(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          if (body.containsKey("namesIncluded") && body.getBoolean("namesIncluded")) {
            String storageType = null;
            if (body.containsKey(Field.FOLDERS.getName())
                && !body.getJsonArray(Field.FOLDERS.getName()).isEmpty()) {
              JsonObject folder = body.getJsonArray(Field.FOLDERS.getName()).getJsonObject(0);
              JsonObject parsedId = Utils.parseItemId(folder.getString(Field.ID.getName()));
              storageType = parsedId.getString(Field.STORAGE_TYPE.getName());
            } else {
              if (body.containsKey(Field.FILES.getName())
                  && !body.getJsonArray(Field.FILES.getName()).isEmpty()) {
                JsonObject files = body.getJsonArray(Field.FILES.getName()).getJsonObject(0);
                JsonObject parsedId = Utils.parseItemId(files.getString(Field.ID.getName()));
                storageType = parsedId.getString(Field.STORAGE_TYPE.getName());
              } else {
                simpleResponse(
                    routingContext,
                    message,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "Nothing to restore"));
              }
            }
            JsonObject sendData = new JsonObject()
                .put(
                    Field.FILES.getName(),
                    body.containsKey(Field.FILES.getName())
                        ? body.getJsonArray(Field.FILES.getName())
                        : new JsonArray())
                .put(
                    Field.FOLDERS.getName(),
                    body.containsKey(Field.FOLDERS.getName())
                        ? body.getJsonArray(Field.FOLDERS.getName())
                        : new JsonArray())
                .put(Field.STORAGE_TYPE.getName(), storageType)
                .put("namesIncluded", true)
                .mergeIn(message.result().body());
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".restoreMultiple",
                sendData,
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                  renameObjects(optionalSegment.orElse(null), routingContext, message, body)
                      .onComplete(result -> {
                        if (!result.succeeded()) {
                          routingContext
                              .response()
                              .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                              .end(new JsonObject()
                                  .put(Field.MESSAGE.getName(), result.cause().toString())
                                  .encodePrettily());
                        } else {
                          simpleResponse(
                              routingContext,
                              message1,
                              Utils.getLocalizedString(
                                  RequestUtils.getLocale(routingContext),
                                  "CouldNotMoveItemsToTrash"));
                        }
                      });
                });
          } else {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".restoreMultiple",
                message.result().body().mergeIn(body),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotMoveItemsToTrash")));
          }
        });
  }

  private void doEraseBatch(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".eraseMultiple",
              message.result().body().mergeIn(body),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotMoveItemsToTrash")));
        });
  }

  private void doTrashBatch(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".trashMultiple",
              message
                  .result()
                  .body()
                  .mergeIn(body)
                  .put("confirmed", routingContext.request().getHeader("confirmed")),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotMoveItemsToTrash")));
        });
  }

  private void doTrashFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          if (fileId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".trash",
                message
                    .result()
                    .body()
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(
                        "headerFolder",
                        routingContext.request().getHeader(Field.FOLDER_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotMoveFileToTrash")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FileidIsRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doDeleteFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }

          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          if (fileId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".deleteFile",
                message
                    .result()
                    .body()
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(
                        Field.FOLDER_ID.getName(),
                        routingContext.request().getHeader(Field.FOLDER_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotDeleteFile")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FileidIsRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doUploadVersion(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          if (Utils.isStringNotNullOrEmpty(fileId)) {
            String presignedUploadId =
                routingContext.request().getHeader(Field.PRESIGNED_UPLOAD_ID.getName());
            boolean isPresignedUpload = Utils.isStringNotNullOrEmpty(presignedUploadId);
            if ((routingContext.fileUploads() != null
                    && !routingContext.fileUploads().isEmpty())
                || isPresignedUpload) {
              JsonObject requestBody = message
                  .result()
                  .body()
                  .mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName())
                      .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)));
              byte[] data = null;
              if (!isPresignedUpload) {
                // let's allow only single file
                FileUpload fileUpload = routingContext.fileUploads().iterator().next();
                Buffer uploaded =
                    vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
                data = uploaded.getBytes();
              } else {
                requestBody.put(Field.PRESIGNED_UPLOAD_ID.getName(), presignedUploadId);
              }
              eb_request(
                  optionalSegment.orElse(null),
                  GridFSModule.address + ".uploadVersion",
                  MessageUtils.generateBuffer(
                      optionalSegment.orElse(null),
                      requestBody,
                      data,
                      RequestUtils.getLocale(routingContext)),
                  (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotUploadVersion")));
            } else {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(
                          Field.MESSAGE.getName(),
                          Utils.getLocalizedString(
                              RequestUtils.getLocale(routingContext), "ThereIsNoFileInTheRequest"))
                      .encodePrettily());
            }

          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FileIdIsRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doPromoteVersion(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String versionId = routingContext.request().getParam(Field.VERSION_ID.getName());
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          if (versionId != null && fileId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".promoteVersion",
                message
                    .result()
                    .body()
                    .put(Field.VER_ID.getName(), versionId)
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotRestoreVersion")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext),
                            "VersionidAndFileidAreRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doMarkVersionAsPrinted(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String printedVersionId = routingContext.request().getParam(Field.VERSION_ID.getName());
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          if (Objects.nonNull(fileId) && Objects.nonNull(printedVersionId)) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".markVersionAsPrinted",
                message
                    .result()
                    .body()
                    .put(Field.PRINTED_VERSION_ID.getName(), printedVersionId)
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotMarkVersionAsPrinted")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatusCodes.BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext),
                            "VersionidAndFileidAreRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doUpdateFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          if (fileId == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FileidIsRequired"))
                    .encodePrettily());
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          String folderId = body.getString(Field.FOLDER_ID.getName());
          String fileName = body.getString(Field.FILE_NAME_C.getName());
          JsonObject share = body.getJsonObject(Field.SHARE.getName());
          JsonArray deShare = body.getJsonArray("deshare");
          String tryShare = body.getString("tryShare");
          String newOwner = body.getString("newOwner");
          if (folderId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".moveFile",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.FOLDER_ID.getName(), folderId)
                    .put(
                        Field.OWNER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message16 -> simpleResponse(
                    routingContext,
                    message16,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotMoveFile")));
          } else if (fileName != null) {
            String folderIdH = routingContext.request().getHeader(Field.FOLDER_ID.getName());
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".renameFile",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), fileId)
                    .put(Field.NAME.getName(), fileName)
                    .put(
                        Field.OWNER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(Field.FOLDER_ID.getName(), folderIdH)
                    .put(
                        Field.SESSION_ID.getName(),
                        message.result().body().getString(Field.SESSION_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message12 -> simpleResponse(
                    routingContext,
                    message12,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotRenameFile")));
          } else if (share != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".shareFile",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), fileId)
                    .put("isUpdate", body.getBoolean("isUpdate"))
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(Field.SHARE.getName(), share)
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.USERNAME.getName()))
                    .put(
                        Field.DEVICE.getName(),
                        message.result().body().getString(Field.DEVICE.getName()))
                    .put(
                        Field.SESSION_ID.getName(),
                        message.result().body().getString(Field.SESSION_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotShareFile")));
          } else if (newOwner != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".changeFileOwner",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), fileId)
                    .put("newOwner", newOwner)
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.USERNAME.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message13 -> simpleResponse(
                    routingContext,
                    message13,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotChangeOwnerOfTheFile")));
          } else if (deShare != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".deShareFile",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), fileId)
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put("deshare", deShare)
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.USERNAME.getName()))
                    .put(
                        Field.DEVICE.getName(),
                        message.result().body().getString(Field.DEVICE.getName()))
                    .put(
                        Field.SESSION_ID.getName(),
                        message.result().body().getString(Field.SESSION_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message14 -> simpleResponse(
                    routingContext,
                    message14,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUnshareFile")));
          } else if (tryShare != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".tryShare",
                message
                    .result()
                    .body()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), fileId)
                    .put("tryShare", tryShare)
                    .put(Field.IS_FOLDER.getName(), false)
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message15 -> simpleResponse(
                    routingContext,
                    message15,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext),
                        "CouldNotCheckPossibilityOfSharing")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "NoDataForUpdateIsSpecified"))
                    .encodePrettily());
          }
        });
  }

  private void doRequestFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              MailUtil.address + ".requestAccess",
              message
                  .result()
                  .body()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotRequestAccess")));
        });
  }

  private void doCloneFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          message.result().body().put(Field.FOLDER_ID.getName(), folderId);
          String folderName;
          JsonObject body = Utils.getBodyAsJson(routingContext);
          folderName = body.getString(Field.FOLDER_NAME.getName());
          if (folderName == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "folderNameIsRequired"))
                    .encodePrettily());
          } else {
            message.result().body().mergeIn(body);
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".cloneFolder",
                message
                    .result()
                    .body()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotCloneFolder")));
          }
        });
  }

  private void doCloneFile(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
          message.result().body().put(Field.FILE_ID.getName(), fileId);
          JsonObject body = Utils.getBodyAsJson(routingContext);

          String fileName = body.getString(Field.FILE_NAME_C.getName());
          if (fileName == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FilenameIsRequired"))
                    .encodePrettily());
          } else {
            message.result().body().mergeIn(body);
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".cloneFile",
                message
                    .result()
                    .body()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotCloneFile")));
          }
        });
  }

  private void doUpdateFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          JsonObject body = Utils.getBodyAsJson(routingContext);

          String parentId = body.getString(Field.PARENT_ID.getName());
          String folderName = body.getString(Field.FOLDER_NAME.getName());
          JsonObject share = body.getJsonObject(Field.SHARE.getName());
          JsonArray deShare = body.getJsonArray("deshare");
          String tryShare = body.getString("tryShare");
          String newOwner = body.getString("newOwner");
          if (parentId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".moveFolder",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.FOLDER_ID.getName(), folderId)
                    .put(Field.PARENT_ID.getName(), parentId)
                    .put(
                        Field.OWNER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.EMAIL.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotMoveFolder")));
          } else if (folderName != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".renameFolder",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), folderId)
                    .put(Field.NAME.getName(), folderName)
                    .put(
                        Field.OWNER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(
                        Field.SESSION_ID.getName(),
                        message.result().body().getString(Field.SESSION_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message12 -> simpleResponse(
                    routingContext,
                    message12,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotRenameFolder")));
          } else if (share != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".shareFolder",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), folderId)
                    .put(Field.SHARE.getName(), share)
                    .put("isUpdate", body.getBoolean("isUpdate"))
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.USERNAME.getName()))
                    .put(
                        Field.DEVICE.getName(),
                        message.result().body().getString(Field.DEVICE.getName()))
                    .put(
                        Field.SESSION_ID.getName(),
                        message.result().body().getString(Field.SESSION_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message13 -> simpleResponse(
                    routingContext,
                    message13,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotShareFolder")));
          } else if (newOwner != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".changeFolderOwner",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), folderId)
                    .put("newOwner", newOwner)
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.USERNAME.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message14 -> simpleResponse(
                    routingContext,
                    message14,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotChangeOwnerOfTheFolder")));
          } else if (deShare != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".deShareFolder",
                new JsonObject()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), folderId)
                    .put("deshare", deShare)
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        message.result().body().getString(Field.STORAGE_TYPE.getName()))
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        message.result().body().getString(Field.EXTERNAL_ID.getName()))
                    .put(
                        Field.USERNAME.getName(),
                        message.result().body().getString(Field.USERNAME.getName()))
                    .put(
                        Field.SESSION_ID.getName(),
                        message.result().body().getString(Field.SESSION_ID.getName())),
                (Handler<AsyncResult<Message<JsonObject>>>) message15 -> simpleResponse(
                    routingContext,
                    message15,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUnshareFolder")));
          } else if (tryShare != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".tryShare",
                message
                    .result()
                    .body()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(Field.ID.getName(), folderId)
                    .put("tryShare", tryShare)
                    .put(Field.IS_FOLDER.getName(), true),
                (Handler<AsyncResult<Message<JsonObject>>>) message16 -> simpleResponse(
                    routingContext,
                    message16,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext),
                        "CouldNotCheckPossibilityOfSharing")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "No data for update is specified")
                    .encodePrettily());
          }
        });
  }

  private void doGetThumbnail(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getThumbnail",
              message
                  .result()
                  .body()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) event -> simpleResponse(
                  routingContext,
                  event,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetInfoOfTheObject")));
        });
  }

  // This is for local checks so far.
  // Enabling it publically will create security breach as authentication is not required
  //    private void doGetIsTrash(RoutingContext routingContext) {
  //        Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
  //        String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
  //        String storageType = routingContext.request().getHeader(Field.STORAGE_TYPE.getName());
  //        String userId = routingContext.request().getHeader(Field.USER_ID.getName());
  //        // get info of any file of any storage
  //        JsonObject infoObj = new JsonObject().put(Field.FILE_ID.getName(), fileId);
  //        if (storageType != null && !storageType.trim().isEmpty() &&
  //                userId != null && !userId.trim().isEmpty())
  //            infoObj.put(Field.STORAGE_TYPE.getName(), storageType).put(Field.USER_ID.getName
  //            (), userId);
  //        ebRequestWithMetrics()(optionalSegment.orElse(null), routingContext, GridFSModule
  //        .address + ".getTrashedInfo",
  //                infoObj,
  //                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse
  //                (routingContext, message1, Utils.getLocalizedString(routingContext.request()
  //                .headers().get(Field.LOCALE.getName()), "CouldNotGetInfoOfTheObject")));
  //
  //    }

  private void doGetObjectInfo(RoutingContext routingContext) {
    JsonObject requestParameters = new TokenVerifier(routingContext).getParameters();
    String token = requestParameters.getString(Field.TOKEN.getName());
    String versionId = requestParameters.getString(Field.VERSION_ID.getName());
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject jsonId = Utils.parseItemId(
        routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName());
    // 07.12 changes for box prototype; later switch to new encapsulation system
    String mode = jsonId.getString("encapsulationMode");
    if (mode != null && mode.equals("0")) {
      String folderId = requestParameters.getString(Field.FOLDER_ID.getName());
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".getObjectInfo",
          new JsonObject()
              .put(Field.FOLDER_ID.getName(), folderId)
              .put(Field.USER_ID.getName(), "BOX_USER") // just in case
              .put(Field.IS_ADMIN.getName(), false)
              .put(Field.LOCALE.getName(), requestParameters.getString(Field.LOCALE.getName()))
              .put("headerFolder", requestParameters.getString(Field.FOLDER_ID.getName()))
              .put("encapsulationMode", mode)
              .mergeIn(jsonId),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  requestParameters.getString(Field.LOCALE.getName()),
                  "CouldNotGetInfoOfTheObject")));

    } else if (!Utils.isStringNotNullOrEmpty(token)) {
      String sessionId = getSessionCookie(routingContext);
      if (sessionId != null) {
        if (!new AuthProvider().authenticate(routingContext, sessionId)) {
          return;
        }

        if (!isUserAdminAndIsStorageAvailable(routingContext)) {
          return;
        }

        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            AuthManager.address + ".additionalAuth",
            AuthManager.getAuthData(routingContext),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> {
              if (!isAuthSuccessful(routingContext, message)) {
                return;
              }
              String folderId = requestParameters.getString(Field.FOLDER_ID.getName());
              String storageType = requestParameters.getString(Field.STORAGE_TYPE.getName());
              String externalId = requestParameters.getString(Field.EXTERNAL_ID.getName());
              String full = requestParameters.getString(Field.FULL.getName());
              // get info of any file of any storage
              if (storageType != null
                  && !storageType.trim().isEmpty()
                  && externalId != null
                  && !externalId.trim().isEmpty()) {
                message
                    .result()
                    .body()
                    .put(Field.STORAGE_TYPE.getName(), storageType)
                    .put(Field.EXTERNAL_ID.getName(), externalId);
              }
              message
                  .result()
                  .body()
                  .put(Field.STORAGE_TYPE.getName(), storageType)
                  .put(Field.EXTERNAL_ID.getName(), externalId);
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  GridFSModule.address + ".getObjectInfo",
                  message
                      .result()
                      .body()
                      .put(Field.FOLDER_ID.getName(), folderId)
                      .put(
                          Field.LOCALE.getName(),
                          requestParameters.getString(Field.LOCALE.getName()))
                      .put("headerFolder", requestParameters.getString(Field.FOLDER_ID.getName()))
                      .put(Field.FULL.getName(), full)
                      .put(
                          Field.IS_ADMIN.getName(),
                          message.result().body().getBoolean(Field.IS_ADMIN.getName()))
                      .mergeIn(jsonId),
                  (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          requestParameters.getString(Field.LOCALE.getName()),
                          "CouldNotGetInfoOfTheObject")));
            });
      } else {
        routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
      }
    } else {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".getObjectInfoByToken",
          new JsonObject()
              .put(Field.TOKEN.getName(), token)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.LOCALE.getName(), requestParameters.getString(Field.LOCALE.getName()))
              .put(Field.PASSWORD.getName(), requestParameters.getString(Field.PASSWORD.getName()))
              .mergeIn(jsonId),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  requestParameters.getString(Field.LOCALE.getName()),
                  "CouldNotGetInfoOfTheObjectByToken")));
    }
  }

  private void doGetTrashed(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    JsonObject jsonId = Utils.parseItemId(
        routingContext.request().getParam(Field.FILE_ID.getName()), Field.FILE_ID.getName());
    String mode = jsonId.getString("encapsulationMode");
    if (mode != null && mode.equals("0")) {
      String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          GridFSModule.address + ".getTrashedInfo",
          new JsonObject()
              .put(Field.FOLDER_ID.getName(), folderId)
              .put(Field.USER_ID.getName(), "BOX_USER") // just in case
              .put(Field.IS_ADMIN.getName(), false)
              .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
              .put("headerFolder", routingContext.request().getHeader(Field.FOLDER_ID.getName()))
              .put("encapsulationMode", mode)
              .mergeIn(jsonId),
          (Handler<AsyncResult<Message<JsonObject>>>) message -> simpleResponse(
              routingContext,
              message,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotGetInfoOfTheObject")));

    } else {
      String sessionId = getSessionCookie(routingContext);
      if (sessionId != null) {
        if (!new AuthProvider().authenticate(routingContext, sessionId)) {
          return;
        }

        if (!isUserAdminAndIsStorageAvailable(routingContext)) {
          return;
        }
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            AuthManager.address + ".additionalAuth",
            AuthManager.getAuthData(routingContext),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> {
              if (!isAuthSuccessful(routingContext, message)) {
                return;
              }
              String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
              String storageType = routingContext.request().getHeader(Field.STORAGE_TYPE.getName());
              String externalId = routingContext.request().getHeader(Field.EXTERNAL_ID.getName());
              String full = routingContext.request().getHeader(Field.FULL.getName());
              // get info of any file of any storage
              if (storageType != null
                  && !storageType.trim().isEmpty()
                  && externalId != null
                  && !externalId.trim().isEmpty()) {
                message
                    .result()
                    .body()
                    .put(Field.STORAGE_TYPE.getName(), storageType)
                    .put(Field.EXTERNAL_ID.getName(), externalId);
              }
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  GridFSModule.address + ".getTrashedInfo",
                  message
                      .result()
                      .body()
                      .put(Field.FOLDER_ID.getName(), folderId)
                      .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                      .put(
                          "headerFolder",
                          routingContext.request().getHeader(Field.FOLDER_ID.getName()))
                      .put(Field.FULL.getName(), full)
                      .put(
                          Field.IS_ADMIN.getName(),
                          message.result().body().getBoolean(Field.IS_ADMIN.getName()))
                      .mergeIn(jsonId),
                  (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetInfoOfTheObject")));
            });
      } else {
        routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
      }
    }
  }

  private void doDownloadFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String storageType;
          String storageTypeShort =
              routingContext.request().getHeader(Field.STORAGE_TYPE.getName());
          if (storageTypeShort == null) {
            storageType = message.result().body().getString(Field.STORAGE_TYPE.getName());
          } else {
            storageType = StorageType.shortToLong(storageTypeShort);
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          String token = routingContext.request().getHeader(Field.TOKEN.getName());
          if ((!Utils.isStringNotNullOrEmpty(folderId) && !Utils.isStringNotNullOrEmpty(token))
              || (folderId.equals(BaseStorage.ROOT_FOLDER_ID)
                  && canDownloadRootFolders(storageType.toLowerCase()))
              || (folderId.endsWith(BaseStorage.ROOT_FOLDER_ID)
                  && canDownloadRootFolders(storageType.toLowerCase()))) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CannotDownloadRootFolder"))
                    .encodePrettily());
            return;
          }
          String filter = routingContext.request().getHeader(Field.FILTER.getName());
          Integer start = null;
          if (routingContext.request().headers().contains(Field.START.getName())) {
            start = Integer.valueOf(routingContext.request().getHeader(Field.START.getName()));
          }
          boolean recursive = true;
          if (Utils.isStringNotNullOrEmpty(
              routingContext.request().getHeader(Field.RECURSIVE.getName()))) {
            recursive =
                Boolean.parseBoolean(routingContext.request().getHeader(Field.RECURSIVE.getName()));
          }
          if (Utils.isStringNotNullOrEmpty(filter)) {
            filter = filter.toLowerCase();
          }
          JsonObject nextRequestBody = message.result().body();
          if (token != null) {
            if (routingContext.request().headers().contains("partToDownload")) {
              int partToDownload =
                  Integer.parseInt(routingContext.request().getHeader("partToDownload"));
              nextRequestBody.put("partToDownload", partToDownload);
            }
            nextRequestBody.put(Field.START.getName(), Objects.nonNull(start) ? start : 0);
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + (token == null ? ".requestFolderZip" : ".getFolderZip"),
              nextRequestBody
                  .put(Field.FOLDER_ID.getName(), folderId)
                  .put(Field.TOKEN.getName(), token)
                  .put(Field.FILTER.getName(), filter)
                  .put(Field.RECURSIVE.getName(), recursive),
              zipMessage -> {
                JsonObject body = null;
                try {
                  if (zipMessage.result().body() instanceof JsonObject) {
                    body = (JsonObject) zipMessage.result().body();
                  }
                } catch (Exception ignore) {
                }
                handleDownloadResponse(routingContext, body, zipMessage);
              });
        });
  }

  private void doDownloadMultiple(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String storageType;
          String storageTypeShort =
              routingContext.request().getHeader(Field.STORAGE_TYPE.getName());
          if (storageTypeShort == null) {
            storageType = message.result().body().getString(Field.STORAGE_TYPE.getName());
          } else {
            storageType = StorageType.shortToLong(storageTypeShort);
          }
          String folderId = routingContext.request().getHeader(Field.FOLDER_ID.getName());
          String token = routingContext.request().getHeader(Field.TOKEN.getName());
          // parent folderId
          if ((!Utils.isStringNotNullOrEmpty(folderId) && !Utils.isStringNotNullOrEmpty(token))
              || (folderId.equals(BaseStorage.ROOT_FOLDER_ID)
                  && canDownloadRootFolders(storageType.toLowerCase()))
              || (folderId.endsWith(BaseStorage.ROOT_FOLDER_ID)
                  && canDownloadRootFolders(storageType.toLowerCase()))) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CannotDownloadRootFolder"))
                    .encodePrettily());
            return;
          }
          String filter = routingContext.request().getHeader(Field.FILTER.getName());
          Integer start = null;
          if (routingContext.request().headers().contains(Field.START.getName())) {
            start = Integer.valueOf(routingContext.request().getHeader(Field.START.getName()));
          }
          boolean recursive = true;
          if (Utils.isStringNotNullOrEmpty(
              routingContext.request().getHeader(Field.RECURSIVE.getName()))) {
            recursive =
                Boolean.parseBoolean(routingContext.request().getHeader(Field.RECURSIVE.getName()));
          }
          if (Utils.isStringNotNullOrEmpty(filter)) {
            filter = filter.toLowerCase();
          }
          JsonObject requestBody = Utils.getBodyAsJson(routingContext);

          JsonObject nextRequestBody = message.result().body();
          if (token != null) {
            if (routingContext.request().headers().contains("partToDownload")) {
              int partToDownload =
                  Integer.parseInt(routingContext.request().getHeader("partToDownload"));
              nextRequestBody.put("partToDownload", partToDownload);
            }
            nextRequestBody.put(Field.START.getName(), Objects.nonNull(start) ? start : 0);
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address
                  + (token == null ? ".requestMultipleDownloadZip" : ".getFolderZip"),
              nextRequestBody
                  .put(Field.FOLDER_ID.getName(), folderId)
                  .put(Field.TOKEN.getName(), token)
                  .put(Field.FILTER.getName(), filter)
                  .put(Field.RECURSIVE.getName(), recursive)
                  .mergeIn(requestBody),
              zipMessage -> {
                JsonObject body = null;
                try {
                  if (zipMessage.result().body() instanceof JsonObject) {
                    body = (JsonObject) zipMessage.result().body();
                  }
                } catch (Exception ignore) {
                }
                handleDownloadResponse(routingContext, body, zipMessage);
              });
        });
  }

  private void handleDownloadResponse(
      RoutingContext routingContext, JsonObject body, AsyncResult<Message<Object>> zipMessage) {
    if (Objects.nonNull(body)) {
      if (body.containsKey(Field.DOWNLOAD_URL.getName())) {
        sendAdditionalDownloadZipHeaders(routingContext, body);
        StreamHelper.downloadToStream(
            routingContext,
            BaseVerticle.HTTP_CLIENT,
            body,
            "CouldNotGetFolderZip",
            body.getInteger(Field.STATUS_CODE.getName()));
        return;
      }
      if (body.containsKey(Field.DATA.getName())
          && Objects.nonNull(body.getBinary(Field.DATA.getName()))) {
        byte[] data = body.getBinary(Field.DATA.getName());
        routingContext
            .response()
            .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(data.length));
        sendAdditionalDownloadZipHeaders(routingContext, body);
        routingContext.response().setStatusCode(body.getInteger(Field.STATUS_CODE.getName()));
        routingContext.response().write(Buffer.buffer(data));
        routingContext.response().end();
        return;
      }
    }
    simpleResponse(
        zipMessage,
        routingContext,
        Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "CouldNotGetFolderZip"));
  }

  private void sendAdditionalDownloadZipHeaders(RoutingContext routingContext, JsonObject body) {
    ArrayList<String> corsExposedHeaders = new ArrayList<>(exposedHeaders);
    corsExposedHeaders.add(Field.DOWNLOADED_PART.getName());
    corsExposedHeaders.add("finalDownload");
    corsExposedHeaders.addAll(Arrays.stream(ExcludeReason.values())
        .map(ExcludeReason::getKey)
        .collect(Collectors.toList()));

    routingContext
        .response()
        .putHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(",", corsExposedHeaders));

    routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");

    if (body.containsKey(Field.DOWNLOADED_PART.getName())) {
      routingContext
          .response()
          .putHeader(
              Field.DOWNLOADED_PART.getName(),
              String.valueOf(body.getInteger(Field.DOWNLOADED_PART.getName()).intValue()));
    }
    if (body.containsKey("finalDownload")) {
      routingContext
          .response()
          .putHeader("finalDownload", String.valueOf(body.getBoolean("finalDownload")));
    }

    // add excluded files to response
    for (ExcludeReason excludeReason : ExcludeReason.values()) {
      String key = excludeReason.getKey();
      if (body.containsKey(key)) {
        routingContext.response().putHeader(key, String.valueOf(body.getJsonArray(key)));
      }
    }
  }

  private boolean canDownloadRootFolders(String storageType) {
    JsonObject downloadRootFolders = config.getCustomProperties().getDownloadRootFoldersAsJson();
    if (downloadRootFolders.containsKey(Field.STORAGES.getName())) {
      JsonObject storages = downloadRootFolders.getJsonObject(Field.STORAGES.getName());
      return !storages.containsKey(storageType) || !storages.getBoolean(storageType);
    }
    return true;
  }

  private void doDownloadFile(RoutingContext routingContext) {
    String format = routingContext.request().getHeader("format");

    if (format == null) {
      doGetFileData(routingContext, true);
      return;
    }

    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!FileFormats.IsValid(format)) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CannotDownloadFile"))
              .encodePrettily());
      return;
    }

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String token = routingContext.request().getHeader(Field.TOKEN.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + (token == null ? ".requestDownload" : ".getDownload"),
              message
                  .result()
                  .body()
                  .put(
                      Field.FILE_ID.getName(),
                      routingContext.request().getParam(Field.FILE_ID.getName()))
                  .put("format", format)
                  .put(Field.TOKEN.getName(), token),
              message1 -> {
                JsonObject body = null;
                try {
                  if (message1.result().body() instanceof JsonObject) {
                    body = (JsonObject) message1.result().body();
                  }
                } catch (Exception ignore) {
                }
                if (token == null
                    || body != null
                        && (ERROR.equals(body.getString(Field.STATUS.getName()))
                            || Integer.valueOf(HttpStatus.SC_ACCEPTED)
                                .equals(body.getInteger(Field.STATUS_CODE.getName())))) {
                  if (body != null) {
                    routingContext
                        .response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                    int statusCode = body.getInteger(Field.STATUS_CODE.getName()) != null
                        ? body.getInteger(Field.STATUS_CODE.getName())
                        : HttpStatus.SC_INTERNAL_SERVER_ERROR;
                    routingContext.response().setStatusCode(statusCode).end(body.encodePrettily());
                  } else {
                    simpleResponse(
                        message1,
                        routingContext,
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CannotDownloadFile"));
                  }
                } else {
                  routingContext
                      .response()
                      .putHeader(
                          HttpHeaders.CONTENT_LENGTH,
                          Integer.toString(((byte[]) message1.result().body()).length));
                  routingContext.response().write(Buffer.buffer((byte[])
                      message1.result().body()));
                  routingContext.response().end();
                }
              });
        });
  }

  private void doDeleteMetadata(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".deleteMetadata",
              message
                  .result()
                  .body()
                  .put(Field.ID.getName(), folderId)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteMetadata")));
        });
  }

  private void doGetMetadata(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getMetadata",
              message
                  .result()
                  .body()
                  .put(Field.ID.getName(), folderId)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetMetadata")));
        });
  }

  private void doUpdateMetadata(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          JsonObject body = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".putMetadata",
              message
                  .result()
                  .body()
                  .put(Field.ID.getName(), folderId)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put("metadata", body),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateMetadata")));
        });
  }

  private void doGetFolderPath(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getFolderPath",
              message
                  .result()
                  .body()
                  .put(Field.FOLDER_ID.getName(), folderId)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetFolderPath")));
        });
  }

  // for GDrive only
  private void doGetFoldersPath(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GDrive.address + ".getBatchPath",
              message
                  .result()
                  .body()
                  .mergeIn(body)
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetFolderPath")));
        });
  }

  private void doCreateFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          final String parentId = body.getString(Field.PARENT_ID.getName());
          final String name = body.getString(Field.NAME.getName());
          if (name == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "NameIsRequired"))
                    .encodePrettily());
          } else {
            message
                .result()
                .body()
                .put(Field.NAME.getName(), name)
                .put(Field.PARENT_ID.getName(), parentId);
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".createFolder",
                message
                    .result()
                    .body()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotCreateNewFolder")));
          }
        });
  }

  private void doUntrashFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          if (folderId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".untrash",
                message
                    .result()
                    .body()
                    .put(Field.FOLDER_ID.getName(), folderId)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotRestoreFolder")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FolderidIsRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doTrashFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String folderId = routingContext.request().getParam(Field.FOLDER_ID.getName());
          if (folderId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                GridFSModule.address + ".trash",
                message
                    .result()
                    .body()
                    .put(Field.FOLDER_ID.getName(), folderId)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotMoveFolderToTrash")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "FolderidIsRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doDeleteFolder(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject json = new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  routingContext.request().getParam(Field.FOLDER_ID.getName()))
              .put(
                  Field.OWNER_ID.getName(),
                  message.result().body().getString(Field.USER_ID.getName()))
              .put(
                  Field.STORAGE_TYPE.getName(),
                  message.result().body().getString(Field.STORAGE_TYPE.getName()))
              .put(
                  Field.EXTERNAL_ID.getName(),
                  message.result().body().getString(Field.EXTERNAL_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".deleteFolder",
              json.put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext)),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteFolder")));
        });
  }

  private void doUploadPreview(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          if (routingContext.fileUploads() != null
              && !routingContext.fileUploads().isEmpty()) {
            String fileId = routingContext.request().getParam(Field.FILE_ID.getName());
            // todo: maybe it makes sense not to wait for the result and send ok without preview
            //  url right away
            routingContext.fileUploads().forEach(fileUpload -> {
              Buffer uploaded = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
              if (uploaded.length() != 0) {
                eb_request(
                    optionalSegment.orElse(null),
                    ThumbnailsManager.address + ".uploadPreview",
                    MessageUtils.generateBuffer(
                        optionalSegment.orElse(null),
                        new JsonObject().put(Field.FILE_ID.getName(), fileId),
                        uploaded.getBytes(),
                        RequestUtils.getLocale(routingContext)),
                    (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                      if (message1.succeeded()
                          && message1
                              .result()
                              .body()
                              .getString(Field.STATUS.getName())
                              .equals(OK)) {
                        routingContext
                            .response()
                            .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                        routingContext.response().end(message1.result().body().encodePrettily());
                      } else {
                        simpleResponse(
                            routingContext,
                            message1,
                            Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext), "CouldNotUploadPreview"));
                      }
                    });
              } else {
                routingContext
                    .response()
                    .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                    .end(new JsonObject()
                        .put(
                            Field.MESSAGE.getName(),
                            Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext), "TheFileIsEmpty"))
                        .encodePrettily());
              }
            });
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "NoFileInTheRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doUploadTmpl(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());
    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          final String id = routingContext.request().headers().get(Field.ID.getName());
          final String templateType = routingContext.request().headers().get("templateType");
          if (routingContext.fileUploads() != null) {
            for (FileUpload fileUpload : routingContext.fileUploads()) {
              // read the file
              Buffer uploaded = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
              if (uploaded.length() == 0) {
                routingContext
                    .response()
                    .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                    .end(new JsonObject()
                        .put(
                            Field.MESSAGE.getName(),
                            Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext),
                                "CouldNotUploadEmptyTemplate"))
                        .encodePrettily());
              } else {
                JsonObject requestJsonObject = new JsonObject()
                    .put(Field.ID.getName(), id)
                    .put(Field.NAME.getName(), fileUpload.fileName())
                    .put("templateId", new ObjectId().toString())
                    .put("templateType", templateType)
                    .put(Field.IS_ADMIN.getName(), isAdmin)
                    .put("sessionInfo", message.result().body());

                eb_request(
                    optionalSegment.orElse(null),
                    TmplVerticle.address + ".create",
                    MessageUtils.generateBuffer(
                        optionalSegment.orElse(null),
                        requestJsonObject,
                        uploaded.getBytes(),
                        RequestUtils.getLocale(routingContext)),
                    (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                      simpleResponse(
                          routingContext,
                          message1,
                          Utils.getLocalizedString(
                              RequestUtils.getLocale(routingContext), "CouldNotSaveTemplate"));
                    });
              }
            }
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "NoFileInTheRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doDeleteTmpl(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());
    boolean byType = (Boolean) routingContext.data().get("byType");

    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String id = routingContext.request().headers().get(Field.ID.getName());

          String templateType = routingContext.request().headers().get("templateType");
          if (byType) {
            String ids = routingContext.request().headers().contains(Field.IDS.getName())
                ? routingContext.request().headers().get(Field.IDS.getName())
                : emptyString;
            if (ids != null && templateType != null) {
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  TmplVerticle.address + ".deleteByType",
                  new JsonObject()
                      .put("templateType", templateType)
                      .put(Field.IDS.getName(), ids)
                      .put(Field.ID.getName(), id)
                      .put(Field.IS_ADMIN.getName(), isAdmin)
                      .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                      .put("sessionInfo", message.result().body()),
                  (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotDeleteTheTemplate")));
            } else {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(
                          Field.MESSAGE.getName(),
                          Utils.getLocalizedString(
                              RequestUtils.getLocale(routingContext), "tmplTypeAndIdIsRequired"))
                      .encodePrettily());
            }
          } else {
            String tmplId = routingContext.request().getParam("tmplId");
            if (tmplId != null) {
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  TmplVerticle.address + ".delete",
                  new JsonObject()
                      .put("templateId", tmplId)
                      .put(Field.ID.getName(), id)
                      .put("templateType", templateType)
                      .put(Field.IS_ADMIN.getName(), isAdmin)
                      .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                      .put("sessionInfo", message.result().body()),
                  (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotDeleteTheTemplate")));
            } else {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(
                          Field.MESSAGE.getName(),
                          Utils.getLocalizedString(
                              RequestUtils.getLocale(routingContext), "tmplIdIsRequired"))
                      .encodePrettily());
            }
          }
        });
  }

  private void doUpdateTmpl(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    boolean isAdmin = (Boolean) routingContext.data().get(Field.IS_ADMIN.getName());
    if (!isUserAdminAndIsStorageAvailable(routingContext, isAdmin)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String tmplId = routingContext.request().getParam("tmplId");
          String id = routingContext.request().headers().get(Field.ID.getName());
          String templateType = routingContext.request().headers().get("templateType");
          JsonObject body = Utils.getBodyAsJson(routingContext);

          if (tmplId != null) {
            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                TmplVerticle.address + ".update",
                body.put("templateId", tmplId)
                    .put(Field.ID.getName(), id)
                    .put("templateType", templateType)
                    .put(Field.IS_ADMIN.getName(), isAdmin)
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put("sessionInfo", message.result().body()),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUpdateTheTemplate")));
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "tmplIdIsRequired"))
                    .encodePrettily());
          }
        });
  }

  private void doGetTemplates(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }

          String id = routingContext.request().headers().get(Field.ID.getName());
          JsonObject sessionInfo = message.result().body();
          String templateType = routingContext.request().headers().get("templateType");
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              TmplVerticle.address + ".getTmpls",
              new JsonObject()
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put("templateType", templateType)
                  .put(Field.ID.getName(), id)
                  .put("sessionInfo", sessionInfo),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetTemplates")));
        });
  }

  private void doGetUserTmpls(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String userId = message.result().body().getString(Field.USER_ID.getName());
          String organizationId =
              message.result().body().getString(Field.ORGANIZATION_ID.getName());
          String groupId = message.result().body().getString("groupId");
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              TmplVerticle.address + ".getAllUserTmpls",
              new JsonObject()
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put(Field.USER_ID.getName(), userId)
                  .put("orgId", (organizationId == null) ? emptyString : organizationId)
                  .put("groupId", (groupId == null) ? emptyString : groupId),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetTemplates")));
        });
  }

  private void doGetTmpl(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }

          String id = routingContext.request().getParam("tmplId");
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              TmplVerticle.address + ".get",
              new JsonObject().put(Field.ID.getName(), id),
              (Handler<AsyncResult<Message<byte[]>>>) message1 -> {
                routingContext
                    .response()
                    .putHeader(
                        HttpHeaders.CONTENT_LENGTH,
                        Integer.toString(message1.result().body().length));
                routingContext.response().write(Buffer.buffer(message1.result().body()));
                routingContext.response().end();
              });
        });
  }

  private boolean checkIfIsCommander(RoutingContext routingContext) {
    boolean isCommander = false;
    if (routingContext.user() != null) {
      User user = routingContext.user();
      if (user.containsKey("session")) {
        JsonObject sessionJson = new JsonObject(user.get("session").toString());
        if (sessionJson.containsKey(Field.DEVICE.getName())) {
          AuthManager.ClientType device = AuthManager.ClientType.getClientType(
              sessionJson.getString(Field.DEVICE.getName(), "BROWSER"));
          isCommander = device.equals(AuthManager.ClientType.COMMANDER);
        }
      }
    }
    return isCommander;
  }

  private void doUpdateXSession(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          message.result().body().mergeIn(Utils.getBodyAsJson(routingContext));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".update",
              message
                  .result()
                  .body()
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put(
                      Field.SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.SESSION_ID.getName()))
                  .put(
                      Field.X_SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.X_SESSION_ID.getName()) != null
                          ? routingContext
                              .request()
                              .getHeader(Field.X_SESSION_ID.getName())
                              .trim()
                          : null)
                  .put("invert", routingContext.request().getHeader("invert"))
                  .put("downgrade", routingContext.request().getHeader("downgrade"))
                  .put(
                      Field.APPLICANT_X_SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.APPLICANT_X_SESSION_ID.getName()))
                  .put(
                      Field.SESSION_STATE.getName(),
                      routingContext.request().getHeader(Field.STATE.getName()))
                  .put("changesAreSaved", false)
                  .put(
                      Field.VERSION_ID.getName(),
                      routingContext.request().getHeader(Field.VERSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) messageAsyncResult -> {
                boolean isCommander = checkIfIsCommander(routingContext);
                simpleResponse(
                    routingContext,
                    messageAsyncResult,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUpdateFileSession"),
                    isCommander);
              });
        });
  }

  private void doSaveXSession(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          boolean addToRecent = true;
          if (Utils.isStringNotNullOrEmpty(routingContext.request().getHeader("addToRecent"))) {
            addToRecent = Boolean.parseBoolean(routingContext.request().getHeader("addToRecent"));
            // if false, the file session will not be saved to Recent Files
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);

          boolean force =
              Boolean.parseBoolean(routingContext.request().headers().get(Field.FORCE.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".save",
              message
                  .result()
                  .body()
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put(Field.FORCE.getName(), force)
                  .put("addToRecent", addToRecent)
                  .put(
                      Field.SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.SESSION_ID.getName()))
                  .put(Field.MODE.getName(), body.getString(Field.MODE.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSaveXenonSession")));
        });
  }

  private void doRemoveXSession(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".remove",
              message
                  .result()
                  .body()
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put(
                      Field.SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.SESSION_ID.getName()))
                  .put(
                      Field.X_SESSION_ID.getName(),
                      routingContext.request().getHeader(Field.X_SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotRemoveXenonSession")));
        });
  }

  private void doRequestXSession(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject requestBody = Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName())
              .put(
                  Field.USER_ID.getName(),
                  message.result().body().getValue(Field.USER_ID.getName()))
              .put(Field.EMAIL.getName(), message.result().body().getValue(Field.EMAIL.getName()))
              .put(Field.NAME.getName(), message.result().body().getValue(Field.NAME.getName()))
              .put(
                  Field.SURNAME.getName(),
                  message.result().body().getValue(Field.SURNAME.getName()))
              .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
              .put(
                  Field.IS_MY_SESSION.getName(),
                  routingContext.request().headers().get(Field.IS_MY_SESSION.getName()))
              .put(
                  Field.X_SESSION_ID.getName(),
                  routingContext.request().getHeader(Field.X_SESSION_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".request",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotRequestXenonSession")));
        });
  }

  private void doDenyXsession(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject requestBody = Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName())
              .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
              .put(
                  Field.X_SESSION_ID.getName(),
                  routingContext.request().getHeader(Field.X_SESSION_ID.getName()))
              .put(
                  Field.REQUEST_X_SESSION_ID.getName(),
                  routingContext.request().getHeader(Field.REQUEST_X_SESSION_ID.getName()))
              .put(Field.EMAIL.getName(), message.result().body().getValue(Field.EMAIL.getName()))
              .put(Field.NAME.getName(), message.result().body().getValue(Field.NAME.getName()))
              .put(
                  Field.SURNAME.getName(),
                  message.result().body().getValue(Field.SURNAME.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".deny",
              requestBody,
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDenyXenonSession")));
        });
  }

  private void doGetXSessions(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          message
              .result()
              .body()
              .mergeIn(Utils.parseItemId(
                  routingContext.request().getParam(Field.FILE_ID.getName()),
                  Field.FILE_ID.getName()));
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".get",
              message
                  .result()
                  .body()
                  .put(
                      Field.LOCALE.getName(),
                      routingContext.request().headers().get(Field.LOCALE.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetListOfXenonSessions")));
        });
  }

  private void doGetUsersFonts(RoutingContext routingContext) {
    JsonObject requestParameters = new TokenVerifier(routingContext).getParameters();
    final String token = requestParameters.getString(Field.TOKEN.getName());
    final String fileId = Utils.parseItemId(
            routingContext.request().getHeader(Field.FILE_ID.getName()), Field.ID.getName())
        .getString(Field.ID.getName());
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    log.info("[FONTS] doGetUsersFonts. FileId: " + fileId + " token: " + token);

    Boolean isCompanyFont = (Boolean) routingContext.data().get("isCompanyFont");
    Boolean isUserFont = (Boolean) routingContext.data().get("isUserFont");

    if (Utils.isStringNotNullOrEmpty(token)) {
      eb_request_with_metrics(
          optionalSegment.orElse(null),
          routingContext,
          FontsVerticle.address + ".getFonts",
          new JsonObject()
              .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
              .put(Field.TOKEN.getName(), token)
              .put(Field.FILE_ID.getName(), fileId)
              .put("isCompanyFont", isCompanyFont)
              .put("isUserFont", isUserFont),
          (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
              routingContext,
              message1,
              Utils.getLocalizedString(
                  RequestUtils.getLocale(routingContext), "CouldNotGetFonts")));
    } else {
      String sessionId = getSessionCookie(routingContext);
      if (sessionId != null) {
        if (!new AuthProvider().authenticate(routingContext, sessionId)) {
          return;
        }

        if (!isUserAdminAndIsStorageAvailable(routingContext)) {
          return;
        }

        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            AuthManager.address + ".additionalAuth",
            AuthManager.getAuthData(routingContext),
            (Handler<AsyncResult<Message<JsonObject>>>) message -> {
              if (!isAuthSuccessful(routingContext, message)) {
                return;
              }
              eb_request_with_metrics(
                  optionalSegment.orElse(null),
                  routingContext,
                  FontsVerticle.address + ".getFonts",
                  message
                      .result()
                      .body()
                      .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                      .put(
                          Field.USER_ID.getName(),
                          message.result().body().getString(Field.USER_ID.getName()))
                      .put("isCompanyFont", isCompanyFont)
                      .put("isUserFont", isUserFont),
                  (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetFonts")));
            });
      } else {
        routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
      }
    }
  }

  private void doGetUsersFont(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    String fontId = routingContext.request().getParam("fontId");
    if (fontId == null) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(new JsonObject()
              .put(Field.MESSAGE.getName(), "fontId is required")
              .encodePrettily());
    } else {
      JsonObject requestParameters = new TokenVerifier(routingContext).getParameters();
      String token = requestParameters.getString(Field.TOKEN.getName());
      String fileId = Utils.parseItemId(
              routingContext.request().getHeader(Field.FILE_ID.getName()), Field.ID.getName())
          .getString(Field.ID.getName());
      if (Utils.isStringNotNullOrEmpty(token)) {
        eb_request_with_metrics(
            optionalSegment.orElse(null),
            routingContext,
            FontsVerticle.address + ".getFontById",
            new JsonObject()
                .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                .put(Field.TOKEN.getName(), token)
                .put(Field.FILE_ID.getName(), fileId)
                .put("fontId", fontId),
            (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
              byte[] body = message1.result().body().getBinary(Field.DATA.getName());
              if (body != null) {
                routingContext.response().putHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                routingContext
                    .response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
                routingContext
                    .response()
                    .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(body.length));
                routingContext.response().write(Buffer.buffer(body));
                routingContext.response().end();
              } else {
                simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotGetFont"));
              }
            });
      } else {
        String sessionId = getSessionCookie(routingContext);
        if (sessionId != null) {
          if (!new AuthProvider().authenticate(routingContext, sessionId)) {
            return;
          }

          if (!isUserAdminAndIsStorageAvailable(routingContext)) {
            return;
          }

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              AuthManager.address + ".additionalAuth",
              AuthManager.getAuthData(routingContext),
              (Handler<AsyncResult<Message<JsonObject>>>) message -> {
                if (!isAuthSuccessful(routingContext, message)) {
                  return;
                }
                eb_request_with_metrics(
                    optionalSegment.orElse(null),
                    routingContext,
                    FontsVerticle.address + ".getFontById",
                    message
                        .result()
                        .body()
                        .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                        .put(
                            Field.USER_ID.getName(),
                            message.result().body().getString(Field.USER_ID.getName()))
                        .put("fontId", fontId),
                    (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                      byte[] body = message1.result().body().getBinary(Field.DATA.getName());
                      if (body != null) {
                        routingContext.response().putHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                        routingContext
                            .response()
                            .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
                        routingContext
                            .response()
                            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(body.length));
                        routingContext.response().write(Buffer.buffer(body));
                        routingContext.response().end();
                      } else {
                        simpleResponse(
                            routingContext,
                            message1,
                            Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext), "CouldNotGetFont"));
                      }
                    });
              });
        } else {
          routingContext.fail(new HttpException(HttpStatus.SC_UNAUTHORIZED));
        }
      }
    }
  }

  private void doUploadUsersFont(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    Boolean isUploadForCompany = (Boolean) routingContext.data().get("isUploadForCompany");
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          if (routingContext.fileUploads() != null
              && !routingContext.fileUploads().isEmpty()) {
            FileUpload fileUpload = routingContext.fileUploads().iterator().next();
            Buffer uploaded = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());

            eb_request_with_metrics(
                optionalSegment.orElse(null),
                routingContext,
                FontsVerticle.address + ".uploadFont",
                message
                    .result()
                    .body()
                    .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                    .put(
                        Field.USER_ID.getName(),
                        message.result().body().getString(Field.USER_ID.getName()))
                    .put(Field.FILE_NAME.getName(), fileUpload.fileName())
                    .put("isUploadForCompany", isUploadForCompany)
                    .put("bytes", uploaded.getBytes()),
                (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                    routingContext,
                    message1,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotUploadFont")));

          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "ThereIsNoFileInTheRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doDeleteUsersFont(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String fontId = routingContext.request().getParam("fontId");
          if (fontId == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), "fontId is required")
                    .encodePrettily());
          }

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              FontsVerticle.address + ".deleteFont",
              message
                  .result()
                  .body()
                  .put(Field.LOCALE.getName(), RequestUtils.getLocale(routingContext))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put("fontId", fontId),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteFont")));
        });
  }

  private void doGetNonce(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".getNonce",
        new JsonObject(),
        (Handler<AsyncResult<Message<JsonObject>>>) messageAsyncResult -> simpleResponse(
            routingContext,
            messageAsyncResult,
            Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "CouldnNotAuth")));
  }

  private void doGetLongNonce(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".getLongNonce",
        new JsonObject(),
        (Handler<AsyncResult<Message<JsonObject>>>) messageAsyncResult -> simpleResponse(
            routingContext,
            messageAsyncResult,
            Utils.getLocalizedString(RequestUtils.getLocale(routingContext), "CouldnNotAuth")));
  }

  private void doUpdateThumbnailGeneration(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body;
          try {
            body = Utils.getBodyAsJson(routingContext);
          } catch (Exception e) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE)
                .end();
            return;
          }
          updateThumbnailGeneration(optionalSegment.orElse(null), routingContext, body);
        });
  }

  private void doGetBlockLibraries(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".getBlockLibraries",
              new JsonObject()
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      routingContext.request().headers().get(Field.OWNER_ID.getName()))
                  .put(
                      Field.OWNER_TYPE.getName(),
                      routingContext.request().headers().get(Field.OWNER_TYPE.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetBlockLibraries")));
        });
  }

  private void doCreateBlockLibrary(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".createBlockLibrary",
              Utils.getBodyAsJson(routingContext)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCreateBlockLibrary")));
        });
  }

  private void doUpdateBlockLibrary(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".updateBlockLibrary",
              Utils.getBodyAsJson(routingContext)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(Field.LIB_ID.getName(), libId),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateBlockLibrary")));
        });
  }

  private void doGetBlockLibraryInfo(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".getBlockLibraryInfo",
              new JsonObject()
                  .put(Field.LIB_ID.getName(), libId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetBlockLibraryInfo")));
        });
  }

  private void doDeleteBlockLibrary(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".deleteBlockLibraries",
              new JsonObject()
                  .put(Field.LIBRARIES.getName(), new JsonArray().add(libId))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteBlockLibrary")));
        });
  }

  private void doDeleteBlockLibraries(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    JsonArray body = Utils.getBodyAsJsonArray(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".deleteBlockLibraries",
              new JsonObject()
                  .put(Field.LIBRARIES.getName(), body)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteBlockLibrary")));
        });
  }

  private void doUploadBlock(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          if (routingContext.fileUploads() != null
              && !routingContext.fileUploads().isEmpty()) {
            Iterator<FileUpload> it = routingContext.fileUploads().iterator();
            if (it.hasNext()) {
              FileUpload fileUpload = it.next();
              Buffer block = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
              if (block.getBytes().length > 0) {
                JsonObject metadata = new JsonObject();
                for (Map.Entry<String, String> entry :
                    routingContext.request().formAttributes().entries()) {
                  metadata.put(entry.getKey(), entry.getValue());
                }
                MultiMap headers = routingContext.request().headers();
                eb_request_with_metrics(
                    optionalSegment.orElse(null),
                    routingContext,
                    BlockLibraryManager.address + ".uploadBlock",
                    metadata
                        .put(Field.LIB_ID.getName(), libId)
                        .put(
                            Field.LOCALE.getName(),
                            message.result().body().getString(Field.LOCALE.getName()))
                        .put(
                            Field.USER_ID.getName(),
                            message.result().body().getString(Field.USER_ID.getName()))
                        .put(Field.FILE_NAME_C.getName(), fileUpload.fileName())
                        .put(Field.NAME.getName(), headers.get(Field.NAME.getName()))
                        .put(Field.BLOCK_CONTENT.getName(), block.getBytes())
                        .put(
                            Field.EMAIL.getName(),
                            message.result().body().getString(Field.EMAIL.getName()))
                        .put(
                            Field.DEVICE.getName(),
                            message.result().body().getString(Field.DEVICE.getName()))
                        .put(
                            Field.SESSION_ID.getName(),
                            message.result().body().getString(Field.SESSION_ID.getName())),
                    (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                        routingContext,
                        message1,
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "CouldNotUploadBlock")));

              } else {
                routingContext
                    .response()
                    .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                    .end(new JsonObject()
                        .put(
                            Field.MESSAGE.getName(),
                            Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext),
                                "ThereIsNoFileInTheRequest"))
                        .encodePrettily());
              }
            }
          } else {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        Utils.getLocalizedString(
                            RequestUtils.getLocale(routingContext), "ThereIsNoFileInTheRequest"))
                    .encodePrettily());
          }
        });
  }

  private void doUpdateBlock(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          String blockId = routingContext.request().params().get(Field.ITEM_ID.getName());
          JsonObject metadata = new JsonObject();
          if (routingContext.fileUploads() != null
              && !routingContext.fileUploads().isEmpty()) {
            Iterator<FileUpload> it = routingContext.fileUploads().iterator();
            if (it.hasNext()) {
              FileUpload fileUpload = it.next();
              Buffer block = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
              metadata.put(Field.BLOCK_CONTENT.getName(), block.getBytes());
              metadata.put(Field.FILE_NAME_C.getName(), fileUpload.fileName());
            }
          }
          for (Map.Entry<String, String> entry :
              routingContext.request().formAttributes().entries()) {
            metadata.put(entry.getKey(), entry.getValue());
          }

          MultiMap headers = routingContext.request().headers();
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".updateBlock",
              metadata
                  .put(Field.LIB_ID.getName(), libId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(Field.NAME.getName(), headers.get(Field.NAME.getName()))
                  .put(Field.DESCRIPTION.getName(), headers.get(Field.DESCRIPTION.getName()))
                  .put(Field.BLOCK_ID.getName(), blockId)
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateBlock")));
        });
  }

  private void doGetBlocks(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".getBlocks",
              new JsonObject()
                  .put(Field.LIB_ID.getName(), libId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetBlocks")));
        });
  }

  private void doDeleteBlock(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          String blockId = routingContext.request().params().get(Field.ITEM_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".deleteBlocks",
              new JsonObject()
                  .put(Field.LIB_ID.getName(), libId)
                  .put(Field.BLOCKS.getName(), new JsonArray().add(blockId))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteBlock")));
        });
  }

  private void doDeleteBlocks(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          JsonArray body = Utils.getBodyAsJsonArray(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".deleteBlocks",
              new JsonObject()
                  .put(Field.LIB_ID.getName(), libId)
                  .put(Field.BLOCKS.getName(), body)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteBlock")));
        });
  }

  private void doGetBlockInfo(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          String blockId = routingContext.request().params().get(Field.ITEM_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".getBlockInfo",
              new JsonObject()
                  .put(Field.LIB_ID.getName(), libId)
                  .put(Field.BLOCK_ID.getName(), blockId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetBlockInfo")));
        });
  }

  private void doGetBlockContent(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          String blockId = routingContext.request().params().get(Field.ITEM_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".getBlockContent",
              new JsonObject()
                  .put(Field.LIB_ID.getName(), libId)
                  .put(Field.BLOCK_ID.getName(), blockId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> {
                if (message1.succeeded()
                    && message1
                        .result()
                        .body()
                        .getString(Field.STATUS.getName())
                        .equals(OK)) {
                  byte[] data = message1.result().body().getBinary(Field.DATA.getName());
                  routingContext
                      .response()
                      .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
                  routingContext
                      .response()
                      .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(data.length));
                  routingContext.response().write(Buffer.buffer(data));
                  routingContext.response().end();
                } else {
                  simpleResponse(
                      routingContext,
                      message1,
                      Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetBlockContent"));
                }
              });
        });
  }

  private void doSearchBlockLibrary(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().headers().get(Field.LIB_ID.getName());
          String type = routingContext.request().headers().get(Field.TYPE.getName());
          String term = routingContext.request().getParam("term");
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".searchBlockLibrary",
              new JsonObject()
                  .put("term", term)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName()))
                  .put(Field.TYPE.getName(), type)
                  .put(Field.LIB_ID.getName(), libId),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotSearchBlockLibrary")));
        });
  }

  private void doShareBlockLibrary(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".shareBlockLibrary",
              Utils.getBodyAsJson(routingContext)
                  .put(Field.LIB_ID.getName(), libId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotShareBlockLibrary")));
        });
  }

  private void doUnShareBlockLibrary(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".unShareBlockLibrary",
              Utils.getBodyAsJson(routingContext)
                  .put(Field.LIB_ID.getName(), libId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUnShareBlockLibrary")));
        });
  }

  private void doShareBlock(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          String blockId = routingContext.request().params().get(Field.ITEM_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".shareBlock",
              Utils.getBodyAsJson(routingContext)
                  .put(Field.LIB_ID.getName(), libId)
                  .put(Field.BLOCK_ID.getName(), blockId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotShareBlock")));
        });
  }

  private void doUnShareBlock(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String libId = routingContext.request().params().get(Field.LIB_ID.getName());
          String blockId = routingContext.request().params().get(Field.ITEM_ID.getName());
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              BlockLibraryManager.address + ".unShareBlock",
              Utils.getBodyAsJson(routingContext)
                  .put(Field.LIB_ID.getName(), libId)
                  .put(Field.BLOCK_ID.getName(), blockId)
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.EMAIL.getName(),
                      message.result().body().getString(Field.EMAIL.getName()))
                  .put(
                      Field.DEVICE.getName(),
                      message.result().body().getString(Field.DEVICE.getName()))
                  .put(
                      Field.SESSION_ID.getName(),
                      message.result().body().getString(Field.SESSION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUnShareBlock")));
        });
  }

  private void doGetResourceFolderContent(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String resourceType = routingContext.request().params().get(Field.TYPE.getName());
          String folderId;
          if (routingContext.request().params().contains(Field.FOLDER_ID.getName())) {
            folderId = routingContext.request().params().get(Field.FOLDER_ID.getName());
          } else {
            log.info("Getting root folder content for " + resourceType);
            folderId = BaseResource.rootFolderId;
          }

          String ownerType = getOwnerTypeForResources(routingContext, true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address + ".getFolderContent",
              new JsonObject()
                  .put(Field.FOLDER_ID.getName(), folderId)
                  .put(Field.RESOURCE_TYPE.getName(), resourceType)
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put("objectFilter", routingContext.request().headers().get("objectFilter"))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetResources")));
        });
  }

  private void doCreateResourceObject(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String objectType = routingContext.request().headers().get(Field.OBJECT_TYPE.getName());
          String resourceType = routingContext.request().params().get(Field.TYPE.getName());
          String folderId = routingContext.request().params().get(Field.FOLDER_ID.getName());
          if (!Utils.isStringNotNullOrEmpty(folderId)) {
            folderId = BaseResource.rootFolderId;
            log.info("Creating object in root folder for " + resourceType);
          }
          JsonObject data = new JsonObject();
          if (objectType.equalsIgnoreCase(ObjectType.FILE.name())) {
            boolean fileExist = false;
            if (routingContext.fileUploads() != null
                && !routingContext.fileUploads().isEmpty()) {
              Iterator<FileUpload> it = routingContext.fileUploads().iterator();
              if (it.hasNext()) {
                FileUpload fileUpload = it.next();
                Buffer file = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
                if (file.getBytes().length > 0) {
                  data.put(Field.FILE_CONTENT.getName(), file.getBytes());
                  data.put(Field.FILE_SIZE.getName(), file.getBytes().length);
                  data.put(Field.FILE_NAME_C.getName(), fileUpload.fileName());
                  fileExist = true;
                }
              }
            }
            if (!fileExist) {
              routingContext
                  .response()
                  .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                  .end(new JsonObject()
                      .put(
                          Field.MESSAGE.getName(),
                          Utils.getLocalizedString(
                              RequestUtils.getLocale(routingContext), "ThereIsNoFileInTheRequest"))
                      .encodePrettily());
              return;
            }
          }

          if (routingContext
              .request()
              .headers()
              .get(HttpHeaders.CONTENT_TYPE)
              .startsWith("multipart/form-data")) {
            for (Map.Entry<String, String> entry :
                routingContext.request().formAttributes().entries()) {
              data.put(entry.getKey(), entry.getValue());
            }
          } else if (routingContext
              .request()
              .headers()
              .get(HttpHeaders.CONTENT_TYPE)
              .equals("application/json")) {
            data.mergeIn(Utils.getBodyAsJson(routingContext));
          }
          String ownerType = getOwnerTypeForResources(routingContext, true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address + ".createObject",
              data.put(Field.FOLDER_ID.getName(), folderId)
                  .put(Field.RESOURCE_TYPE.getName(), resourceType)
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put(Field.OBJECT_TYPE.getName(), objectType)
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCreateResource")));
        });
  }

  private void doUpdateResourceObject(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String objectType = routingContext.request().headers().get(Field.OBJECT_TYPE.getName());
          JsonObject data = new JsonObject();
          if (objectType.equalsIgnoreCase(ObjectType.FILE.name())) {
            if (routingContext.fileUploads() != null
                && !routingContext.fileUploads().isEmpty()) {
              Iterator<FileUpload> it = routingContext.fileUploads().iterator();
              if (it.hasNext()) {
                FileUpload fileUpload = it.next();
                Buffer file = vertx.fileSystem().readFileBlocking(fileUpload.uploadedFileName());
                if (file.getBytes().length > 0) {
                  data.put(Field.FILE_CONTENT.getName(), file.getBytes());
                  data.put(Field.FILE_SIZE.getName(), file.getBytes().length);
                  data.put(Field.FILE_NAME_C.getName(), fileUpload.fileName());
                } else {
                  routingContext
                      .response()
                      .setStatusCode(HttpStatus.SC_BAD_REQUEST)
                      .end(new JsonObject()
                          .put(
                              Field.MESSAGE.getName(),
                              Utils.getLocalizedString(
                                  RequestUtils.getLocale(routingContext),
                                  "ThereIsNoFileInTheRequest"))
                          .encodePrettily());
                  return;
                }
              }
            }
          }

          if (routingContext
              .request()
              .headers()
              .get(HttpHeaders.CONTENT_TYPE)
              .startsWith("multipart/form-data")) {
            for (Map.Entry<String, String> entry :
                routingContext.request().formAttributes().entries()) {
              data.put(entry.getKey(), entry.getValue());
            }
          } else if (routingContext
              .request()
              .headers()
              .get(HttpHeaders.CONTENT_TYPE)
              .equals("application/json")) {
            data.mergeIn(Utils.getBodyAsJson(routingContext));
          }
          String ownerType = getOwnerTypeForResources(routingContext, true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address + ".updateObject",
              data.put(
                      Field.FOLDER_ID.getName(),
                      routingContext.request().params().get(Field.FOLDER_ID.getName()))
                  .put(
                      Field.OBJECT_ID.getName(),
                      routingContext.request().params().get(Field.ITEM_ID.getName()))
                  .put(
                      Field.RESOURCE_TYPE.getName(),
                      routingContext.request().params().get(Field.TYPE.getName()))
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put(Field.OBJECT_TYPE.getName(), objectType)
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateResource")));
        });
  }

  private void doDeleteResourceObjects(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    JsonArray body = Utils.getBodyAsJsonArray(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          String ownerType = getOwnerTypeForResources(routingContext, true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address + ".deleteObjects",
              new JsonObject()
                  .put(
                      Field.RESOURCE_TYPE.getName(),
                      routingContext.request().params().get(Field.TYPE.getName()))
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put("objects", body)
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotDeleteResources")));
        });
  }

  private void doGetResourceObjectInfo(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          // For resource info: ownerType is not required.
          String ownerType = getOwnerTypeForResources(routingContext, false);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address + ".getObjectInfo",
              new JsonObject()
                  .put(
                      Field.OBJECT_ID.getName(),
                      routingContext.request().params().get(Field.ITEM_ID.getName()))
                  .put(
                      Field.RESOURCE_TYPE.getName(),
                      routingContext.request().params().get(Field.TYPE.getName()))
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put(
                      Field.OBJECT_TYPE.getName(),
                      routingContext.request().headers().get(Field.OBJECT_TYPE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetResourceInfo")));
        });
  }

  private void doGetResourceFolderPath(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          // For resource folder path: ownerType is not required.
          String ownerType = getOwnerTypeForResources(routingContext, false);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address + ".getFolderPath",
              new JsonObject()
                  .put(
                      Field.OBJECT_ID.getName(),
                      routingContext.request().params().get(Field.ITEM_ID.getName()))
                  .put(
                      Field.RESOURCE_TYPE.getName(),
                      routingContext.request().params().get(Field.TYPE.getName()))
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetResourceFolderPath")));
        });
  }

  private void doDownloadResourceObject(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          boolean recursive = true;
          if (Utils.isStringNotNullOrEmpty(
              routingContext.request().getHeader(Field.RECURSIVE.getName()))) {
            recursive =
                Boolean.parseBoolean(routingContext.request().getHeader(Field.RECURSIVE.getName()));
          }
          String downloadToken =
              routingContext.request().headers().get(Field.DOWNLOAD_TOKEN.getName());
          String ownerType = getOwnerTypeForResources(routingContext, true);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              ResourceModule.address
                  + ((downloadToken == null) ? ".requestDownload" : ".getDownload"),
              new JsonObject()
                  .put(
                      Field.OBJECT_ID.getName(),
                      routingContext.request().params().get(Field.ITEM_ID.getName()))
                  .put(
                      Field.RESOURCE_TYPE.getName(),
                      routingContext.request().params().get(Field.TYPE.getName()))
                  .put(Field.OWNER_TYPE.getName(), ownerType)
                  .put(
                      Field.OBJECT_TYPE.getName(),
                      routingContext.request().headers().get(Field.OBJECT_TYPE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName()))
                  .put(
                      Field.OWNER_ID.getName(),
                      getOwnerIdForResources(
                          routingContext, ownerType, message.result().body()))
                  .put(Field.RECURSIVE.getName(), recursive)
                  .put(Field.DOWNLOAD_TOKEN.getName(), downloadToken)
                  .put(
                      Field.ORGANIZATION_ID.getName(),
                      message.result().body().getString(Field.ORGANIZATION_ID.getName())),
              message1 -> {
                boolean success = false;
                if (message1.succeeded()) {
                  if (message1.result().body() instanceof JsonObject) {
                    JsonObject result = (JsonObject) message1.result().body();
                    if (result != null
                        && result.getString(Field.STATUS.getName()).equals(OK)) {
                      routingContext
                          .response()
                          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                      int statusCode = result.getInteger(Field.STATUS_CODE.getName()) != null
                          ? result.getInteger(Field.STATUS_CODE.getName())
                          : HttpStatus.SC_INTERNAL_SERVER_ERROR;
                      routingContext
                          .response()
                          .setStatusCode(statusCode)
                          .end(result.encodePrettily());
                      success = true;
                    }
                  } else if (message1.result().body() instanceof byte[]) {
                    byte[] data = (byte[]) message1.result().body();
                    routingContext
                        .response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
                    routingContext
                        .response()
                        .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(data.length));
                    routingContext.response().end(Buffer.buffer(data));
                    success = true;
                  }
                }
                if (success) {
                  return;
                }
                simpleResponse(
                    message1,
                    routingContext,
                    Utils.getLocalizedString(
                        RequestUtils.getLocale(routingContext), "CouldNotDownloadResource"));
              });
        });
  }

  private void doUpdateAdminStorageAccess(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject body = Utils.getBodyAsJson(routingContext);
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              AuthManager.address + ".updateStorageAccess",
              body.put(
                  Field.USER_ID.getName(),
                  message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) messageAsyncResult -> simpleResponse(
                  routingContext,
                  messageAsyncResult,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotUpdateStorage")));
        });
  }

  private void doGetAdminDisabledStorages(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              AuthManager.address + ".getAdminDisabledStorages",
              new JsonObject(),
              (Handler<AsyncResult<Message<JsonObject>>>) messageAsyncResult -> simpleResponse(
                  routingContext,
                  messageAsyncResult,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetDisabledStorages")));
        });
  }

  private void doCheckFileSave(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    String xSessionId = routingContext.request().headers().get(Field.X_SESSION_ID.getName());
    String baseChangeId = routingContext.request().headers().get(Field.BASE_CHANGE_ID.getName());
    String fileId = routingContext.request().params().get(Field.FILE_ID.getName());

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".checkFileSave",
              message
                  .result()
                  .body()
                  .mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
                  .put(Field.X_SESSION_ID.getName(), xSessionId)
                  .put(Field.BASE_CHANGE_ID.getName(), baseChangeId),
              (Handler<AsyncResult<Message<JsonObject>>>) checkSaveResponse -> simpleResponse(
                  routingContext,
                  checkSaveResponse,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotCheckFileSave")));
        });
  }

  private void doRequestMultipleUpload(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }
    if (!routingContext.request().headers().contains("begin")) {
      routingContext
          .response()
          .setStatusCode(HttpStatusCodes.PRECONDITION_FAILED)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "UploadRequestFlagRequired"))
              .encodePrettily());
      return;
    }
    boolean begin = Boolean.parseBoolean(routingContext.request().headers().get("begin"));
    String uploadRequestId =
        routingContext.request().headers().get(Field.UPLOAD_REQUEST_ID.getName());
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              SimpleFluorine.address + ".requestMultipleUpload",
              new JsonObject()
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put("begin", begin)
                  .put(Field.UPLOAD_REQUEST_ID.getName(), uploadRequestId),
              (Handler<AsyncResult<Message<JsonObject>>>) sendSampleUsageResponse -> simpleResponse(
                  routingContext,
                  sendSampleUsageResponse,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext),
                      "CouldNotCompleteMultipleUploadRequest")));
        });
  }

  private void doGetRolesAndPermissions(RoutingContext routingContext) {
    try {
      JsonObject result = ObjectPermissions.getDefaultRolesAndPermissions();
      routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
      routingContext.response().setStatusCode(HttpStatus.SC_OK).end(result.encodePrettily());
    } catch (Exception ex) {
      log.error(ex);
      routingContext
          .response()
          .setStatusCode(HttpStatusCodes.BAD_REQUEST)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                          RequestUtils.getLocale(routingContext), "CouldNotGetRolesAndPermissions")
                      + " - " + ex.getLocalizedMessage())
              .encodePrettily());
    }
  }

  private void doGetAccountFileSessions(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    JsonObject body = Utils.getBodyAsJson(routingContext);

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              XSessionManager.address + ".getAccountFileSessions",
              new JsonObject()
                  .put(Field.EXTERNAL_ID.getName(), body.getString(Field.ID.getName()))
                  .put(Field.STORAGE_TYPE.getName(), body.getString(Field.TYPE.getName()))
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(
                      Field.LOCALE.getName(),
                      message.result().body().getString(Field.LOCALE.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetAccountFileSessions")));
        });
  }

  private void doGetS3PreSignedUploadURL(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject requestBody = Utils.getBodyAsJson(routingContext);
          String fileContentType = requestBody.getString("fileContentType");
          String fileName = requestBody.getString(Field.FILE_NAME_C.getName());
          String fileId = requestBody.getString(Field.FILE_ID.getName());
          String storageType = message.result().body().getString(Field.STORAGE_TYPE.getName());
          if (Objects.nonNull(fileId)) {
            JsonObject parsedItem = Utils.parseItemId(fileId, Field.FILE_ID.getName());
            fileId = parsedItem.getString(Field.FILE_ID.getName());
            storageType = parsedItem.getString(Field.STORAGE_TYPE.getName());
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              GridFSModule.address + ".getS3PreSignedUploadURL",
              new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType)
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(Field.CONTENT_TYPE.getName(), fileContentType)
                  .put(Field.FILE_NAME_C.getName(), fileName)
                  .put(Field.FILE_ID.getName(), fileId),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetS3PresignedUrl")));
        });
  }

  private String getOwnerTypeForResources(RoutingContext routingContext, boolean isRequired) {
    if (routingContext.request().headers().contains(Field.OWNER_TYPE.getName())
        && Utils.isStringNotNullOrEmpty(
            routingContext.request().headers().get(Field.OWNER_TYPE.getName()))) {
      return routingContext.request().headers().get(Field.OWNER_TYPE.getName());
    } else {
      if (isRequired) {
        return ResourceOwnerType.OWNED.name();
      }
      return null;
    }
  }

  private String getOwnerIdForResources(
      RoutingContext routingContext, String ownerType, JsonObject authObject) {
    if (routingContext.request().headers().contains(Field.OWNER_ID.getName())) {
      return routingContext.request().headers().get(Field.OWNER_ID.getName());
    } else {
      if (Utils.isStringNotNullOrEmpty(ownerType)) {
        if (ownerType.equalsIgnoreCase(ResourceOwnerType.OWNED.name())) {
          if (authObject != null
              && Utils.isStringNotNullOrEmpty(authObject.getString(Field.USER_ID.getName()))) {
            return authObject.getString(Field.USER_ID.getName());
          }
        } else if (ownerType.equalsIgnoreCase(ResourceOwnerType.ORG.name())) {
          if (authObject != null
              && Utils.isStringNotNullOrEmpty(
                  authObject.getString(Field.ORGANIZATION_ID.getName()))) {
            return authObject.getString(Field.ORGANIZATION_ID.getName());
          }
        } else if (ownerType.equalsIgnoreCase(ResourceOwnerType.PUBLIC.name())) {
          return emptyString;
        }
      }
    }
    return null;
  }

  private void doGetUserCapabilities(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    if (!isUserAdminAndIsStorageAvailable(routingContext)) {
      return;
    }

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              UsersVerticle.address + ".getCapabilities",
              new JsonObject()
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotGetUserCapabilities")));
        });
  }

  private void doStopPoll(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();

    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".additionalAuth",
        AuthManager.getAuthData(routingContext),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (!isAuthSuccessful(routingContext, message)) {
            return;
          }
          JsonObject jsonObject = Utils.getBodyAsJson(routingContext);

          eb_request_with_metrics(
              optionalSegment.orElse(null),
              routingContext,
              NextCloud.address + ".stopPoll",
              new JsonObject()
                  .put(
                      Field.USER_ID.getName(),
                      message.result().body().getString(Field.USER_ID.getName()))
                  .put(Field.URL.getName(), jsonObject.getString(Field.URL.getName())),
              (Handler<AsyncResult<Message<JsonObject>>>) message1 -> simpleResponse(
                  routingContext,
                  message1,
                  Utils.getLocalizedString(
                      RequestUtils.getLocale(routingContext), "CouldNotStopPoll")));
        });
  }

  private void updateThumbnailGeneration(
      Entity segment, RoutingContext routingContext, JsonObject body) {
    eb_request(segment, ThumbnailsManager.address + ".updateGeneration", body, (Handler<
            AsyncResult<Message<JsonObject>>>)
        message1 -> simpleResponse(
            routingContext,
            message1,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotUpdateThumbnailGeneration")));
  }

  private void simpleResponse(
      AsyncResult<Message<Object>> message, RoutingContext routingContext, String error) {
    if (message.result() == null || message.failed()) {
      if (message.cause().toString().contains("TIMEOUT")) {
        sendTimeoutResponse(routingContext);
      } else {
        routingContext
            .response()
            .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
            .end(new JsonObject()
                .put(Field.MESSAGE.getName(), message.cause().toString())
                .encodePrettily());
      }
    } else if (((JsonObject) message.result().body())
        .getString(Field.STATUS.getName())
        .equals(OK)) {
      if (((JsonObject) message.result().body()).getInteger(Field.STATUS_CODE.getName()) != null) {
        routingContext
            .response()
            .setStatusCode(
                ((JsonObject) message.result().body()).getInteger(Field.STATUS_CODE.getName()));
      }
      routingContext.response().end(((JsonObject) message.result().body()).encodePrettily());
    } else {
      String authHeader = ((JsonObject) message.result().body()).getString("authHeader");
      if (authHeader != null) {
        routingContext.response().putHeader("Authenticate", authHeader);
      }
      String end = emptyString;
      if (((JsonObject) message.result().body()).containsKey(Field.MESSAGE.getName())
          && ((JsonObject) message.result().body()).getValue(Field.MESSAGE.getName()) != null) {
        if (((JsonObject) message.result().body()).getValue(Field.MESSAGE.getName())
            instanceof String) {
          end = error + ": "
              + ((JsonObject) message.result().body()).getString(Field.MESSAGE.getName());
        } else {
          JsonObject response =
              ((JsonObject) message.result().body()).getJsonObject(Field.MESSAGE.getName());
          if (response.containsKey(Field.MESSAGE.getName())) {
            response.put(
                Field.MESSAGE.getName(),
                error + ": " + response.getString(Field.MESSAGE.getName()));
          }
          end = response.encodePrettily();
          routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        }
      }
      routingContext
          .response()
          .setStatusCode(
              ((JsonObject) message.result().body()).getInteger(Field.STATUS_CODE.getName()))
          .end(end);
    }
  }

  private void setSessionCookie(RoutingContext routingContext, String sessionId) {
    Cookie cookie = Cookie.cookie(Field.SESSION_ID.getName(), sessionId)
        .setPath("/")
        .setMaxAge(60 * 60 * 24 * 365) // 1 year
        .setDomain(config.getCustomProperties().getDomain())
        .setSecure(true);
    // DK: we should be able to set SameSite with Vert.x 3.9
    // (https://github.com/eclipse-vertx/vert.x/pull/3202)
    // But for now let's just use this workaround
    routingContext
        .response()
        .putHeader(HttpHeaders.SET_COOKIE, cookie.encode() + "; SameSite=None;");
    //        routingContext.addCookie(cookie);
  }

  private String getSessionCookie(RoutingContext routingContext) {
    String sessionId;
    Cookie sessionIdCookie = routingContext.request().getCookie(Field.SESSION_ID.getName());
    if (sessionIdCookie != null) {
      sessionId = sessionIdCookie.getValue();
    } else {
      sessionId = routingContext.request().headers().get(Field.SESSION_ID.getName());
    }
    if (sessionId == null || sessionId.trim().isEmpty()) {
      sessionId = routingContext.request().getParam(Field.SESSION_ID.getName());
    }
    return sessionId;
  }

  private void simpleResponse(
      RoutingContext routingContext, AsyncResult<Message<JsonObject>> message, String error) {
    simpleResponse(routingContext, message, error, false);
  }

  private void simpleResponse(
      RoutingContext routingContext,
      AsyncResult<Message<JsonObject>> message,
      String error,
      boolean ignoreMessage) {
    if (routingContext.response().closed()) {
      return;
    }
    if (message.failed() || message.result() == null) {
      if (message.cause().toString().contains("TIMEOUT")) {
        sendTimeoutResponse(routingContext);
      } else {
        routingContext
            .response()
            .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
            .end(new JsonObject()
                .put(Field.MESSAGE.getName(), message.cause().toString())
                .encodePrettily());
      }
    } else if (message.result().body().getString(Field.STATUS.getName()).equals(OK)) {
      String sessionId = message.result().body().getString(Field.SESSION_ID.getName());
      if (sessionId != null) {
        setSessionCookie(routingContext, sessionId);
      }
      JsonObject jsonObject = message.result().body();
      int statusCode = jsonObject.getInteger(Field.STATUS_CODE.getName()) != null
          ? jsonObject.getInteger(Field.STATUS_CODE.getName())
          : HttpStatus.SC_OK;
      routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
      if (ignoreMessage && jsonObject.containsKey(Field.MESSAGE.getName())) {
        jsonObject.remove(Field.MESSAGE.getName());
      }
      routingContext.response().setStatusCode(statusCode).end(jsonObject.encodePrettily());
    } else {
      String authHeader = message.result().body().getString("authHeader");
      if (authHeader != null) {
        routingContext.response().putHeader("Authenticate", authHeader); // WWW-Authenticate
      }
      String end = emptyString;
      String prefix = !error.isEmpty() ? error + ": " : emptyString;
      JsonObject jsonObject = message.result().body();
      if (jsonObject.containsKey(Field.MESSAGE.getName())
          && jsonObject.getValue(Field.MESSAGE.getName()) != null) {
        if (jsonObject.getValue(Field.MESSAGE.getName()) instanceof String) {
          end = prefix + jsonObject.getString(Field.MESSAGE.getName());
        } else {
          JsonObject response = jsonObject.getJsonObject(Field.MESSAGE.getName());
          if (response.containsKey(Field.MESSAGE.getName())) {
            response.put(
                Field.MESSAGE.getName(), prefix + response.getValue(Field.MESSAGE.getName()));
          } else if (response.containsKey(Field.ERROR_MESSAGE.getName())) {
            response.put(
                Field.MESSAGE.getName(), prefix + response.getValue(Field.ERROR_MESSAGE.getName()));
          }
          if (ignoreMessage && response.containsKey(Field.MESSAGE.getName())) {
            response.remove(Field.MESSAGE.getName());
          }
          end = response.encodePrettily();
          routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        }
      }
      routingContext
          .response()
          .setStatusCode(jsonObject.getInteger(Field.STATUS_CODE.getName()))
          .end(end);
    }
  }

  @Override
  public void stop() throws Exception {
    super.stop();
    if (DynamoBusModBase.statsd != null) {
      DynamoBusModBase.statsd.stop();
    }
  }

  private void doHealthCheckup(RoutingContext routingContext) {
    Optional<Entity> optionalSegment = XRayManager.getCurrentSegment();
    eb_request_with_metrics(
        optionalSegment.orElse(null),
        routingContext,
        AuthManager.address + ".healthCheckup",
        new JsonObject(),
        (Handler<AsyncResult<Message<JsonObject>>>) message -> {
          if (routingContext.response().closed()) {
            return;
          }
          if (message.result() == null) {
            routingContext
                .response()
                .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                .end(new JsonObject()
                    .put(Field.MESSAGE.getName(), message.cause().toString())
                    .encodePrettily());
          } else {
            simpleResponse(routingContext, message, emptyString);
          }
        });
  }

  private String headerEncode(String header) {
    // http headers only like ASCII, so we need to encode our result, as we expect non US-ASCII
    // values

    StringBuilder response = new StringBuilder(emptyString);
    byte[] ptext = header.getBytes(StandardCharsets.UTF_8);
    for (byte b : ptext) {
      int v = b & 0xFF;
      response.append("%").append(HEX_ARRAY[v >>> 4]).append(HEX_ARRAY[v & 0x0F]);
    }
    return response.toString();
  }

  private boolean isUserAdmin(RoutingContext context, boolean isAdmin) {
    if (isAdmin && !adminAuth.match(context.user())) {
      context
          .response()
          .setStatusCode(HttpStatus.SC_FORBIDDEN)
          .end(new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(
                      context.request().headers().get(Field.LOCALE.getName()), "ThisIsAnAdminTool"))
              .encodePrettily());
      return false;
    }
    return true;
  }

  private boolean isUserAdminAndIsStorageAvailable(RoutingContext context) {
    boolean isAdmin = (Boolean) context.data().get(Field.IS_ADMIN.getName());
    return isUserAdminAndIsStorageAvailable(context, isAdmin);
  }

  private boolean isUserAdminAndIsStorageAvailable(RoutingContext context, boolean isAdmin) {
    boolean requireStorage = (Boolean) context.data().get("requireStorage");
    return (isUserAdmin(context, isAdmin) && Users.isStorageAvailable(context, requireStorage));
  }

  private boolean isAuthSuccessful(
      RoutingContext routingContext, AsyncResult<Message<JsonObject>> message) {
    if (Objects.nonNull(message)
        && message.succeeded()
        && Objects.nonNull(message.result())
        && Objects.nonNull(message.result().body())) {
      JsonObject result = message.result().body();
      if (!result.getString(Field.STATUS.getName()).equals(OK)) {
        simpleResponse(
            routingContext,
            message,
            Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotAuthorizeRequest"));
        return false;
      }
      return true;
    }
    String errorString = null;
    if (Objects.nonNull(message.cause())) {
      errorString = message.cause().toString();
      log.warn(
          "Auth failed for a request : " + routingContext.request().path() + " : " + errorString);
    }
    String errorMessage = null;
    if (Objects.nonNull(errorString)) {
      if (errorString.contains("TIMEOUT")) {
        sendTimeoutResponse(routingContext);
      } else {
        errorMessage = Utils.getLocalizedString(
                RequestUtils.getLocale(routingContext), "CouldNotAuthorizeRequest")
            + ": " + errorString;
      }
    } else {
      errorMessage = Utils.getLocalizedString(
          RequestUtils.getLocale(routingContext), "SomethingWentWrongInRequestAuth");
    }
    if (Objects.nonNull(errorMessage)) {
      routingContext
          .response()
          .setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
          .end(new JsonObject().put(Field.MESSAGE.getName(), errorMessage).encodePrettily());
    }
    return false;
  }

  private void sendTimeoutResponse(RoutingContext routingContext) {
    routingContext
        .response()
        .setStatusCode(HttpStatus.SC_REQUEST_TIMEOUT)
        .end(new JsonObject()
            .put(
                Field.MESSAGE.getName(),
                Utils.getLocalizedString(
                    routingContext.request().headers().get(Field.LOCALE.getName()),
                    "TimeOutResponse"))
            .encodePrettily());
  }
}
