package com.graebert.storage.config;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.amazonaws.services.secretsmanager.model.InvalidParameterException;
import com.amazonaws.services.secretsmanager.model.InvalidRequestException;
import com.amazonaws.services.secretsmanager.model.ResourceNotFoundException;
import com.google.common.collect.Lists;
import com.graebert.storage.blocklibrary.BlockLibraryManager;
import com.graebert.storage.comment.CommentVerticle;
import com.graebert.storage.fonts.FontsVerticle;
import com.graebert.storage.gridfs.GridFSModule;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.integration.Box;
import com.graebert.storage.integration.DropBox;
import com.graebert.storage.integration.GDrive;
import com.graebert.storage.integration.NextCloud;
import com.graebert.storage.integration.SharePoint;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.TrimbleConnect;
import com.graebert.storage.integration.WebDAV;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.resources.ResourceModule;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.resources.integration.BlockLibrary;
import com.graebert.storage.resources.integration.Fonts;
import com.graebert.storage.resources.integration.Lisp;
import com.graebert.storage.resources.integration.Templates;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.stats.StatsVerticle;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.tmpl.TmplVerticle;
import com.graebert.storage.users.UsersVerticle;
import com.graebert.storage.util.Field;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Map;

public class ServerConfigBuilder {
  private final ServerConfigParser parser;

  public ServerConfigBuilder(JsonObject globalConfig) {
    parser = new ServerConfigParser(globalConfig);
  }

  public ServerConfigParser getParser() {
    return parser;
  }

  public void addVerticles(Map<String, String> verticles) {
    verticles.put(MailUtil.class.getName(), MailUtil.address);
    verticles.put(AuthManager.class.getName(), AuthManager.address);
    verticles.put(UsersVerticle.class.getName(), UsersVerticle.address);
    verticles.put(XSessionManager.class.getName(), XSessionManager.address);
    verticles.put(GridFSModule.class.getName(), GridFSModule.address);
    verticles.put(TmplVerticle.class.getName(), TmplVerticle.address);
    verticles.put(ThumbnailsManager.class.getName(), ThumbnailsManager.address);
    verticles.put(WebSocketManager.class.getName(), WebSocketManager.address);
    verticles.put(CommentVerticle.class.getName(), CommentVerticle.address);
    verticles.put(RecentFilesVerticle.class.getName(), RecentFilesVerticle.address);
    verticles.put(Subscriptions.class.getName(), Subscriptions.address);
    verticles.put(NotificationEvents.class.getName(), NotificationEvents.address);
    verticles.put(StatsVerticle.class.getName(), StatsVerticle.address);
    verticles.put(FontsVerticle.class.getName(), FontsVerticle.address);
    verticles.put(BlockLibraryManager.class.getName(), BlockLibraryManager.address);
    verticles.put(ResourceModule.class.getName(), ResourceModule.address);

    boolean samples = parser.getBoolean(ConfigObject.STORAGES, "SAMPLES", true),
        box = parser.getBoolean(ConfigObject.STORAGES, "BOX", true),
        dropbox = parser.getBoolean(ConfigObject.STORAGES, "DROPBOX", true),
        gdrive = parser.getBoolean(ConfigObject.STORAGES, "GDRIVE", true),
        trimble = parser.getBoolean(ConfigObject.STORAGES, "TRIMBLE", true),
        sharepoint = parser.getBoolean(ConfigObject.STORAGES, "SHAREPOINT", true),
        webdav = parser.getBoolean(ConfigObject.STORAGES, "WEBDAV", true),
        nextcloud = parser.getBoolean(ConfigObject.STORAGES, "NEXTCLOUD", true);

    if (box) {
      verticles.put(Box.class.getName(), Box.address);
    }
    if (gdrive) {
      verticles.put(GDrive.class.getName(), GDrive.address);
    }
    if (dropbox) {
      verticles.put(DropBox.class.getName(), DropBox.address);
    }
    if (trimble) {
      verticles.put(TrimbleConnect.class.getName(), TrimbleConnect.address);
    }
    if (webdav) {
      verticles.put(WebDAV.class.getName(), WebDAV.address);
    }
    if (nextcloud) {
      verticles.put(NextCloud.class.getName(), NextCloud.address);
    }
    if (samples) {
      verticles.put(SimpleFluorine.class.getName(), SimpleFluorine.address);
    }
    if (sharepoint) {
      verticles.put(SharePoint.class.getName(), SharePoint.address);
    }

    boolean
        blockLibrary =
            parser.getBoolean(ConfigObject.RESOURCES, "BLOCKS", Field.ENABLED.getName(), true),
        templates =
            parser.getBoolean(ConfigObject.RESOURCES, "TEMPLATES", Field.ENABLED.getName(), true),
        fonts = parser.getBoolean(ConfigObject.RESOURCES, "FONTS", Field.ENABLED.getName(), true),
        lisp = parser.getBoolean(ConfigObject.RESOURCES, "LISP", Field.ENABLED.getName(), true);

    if (blockLibrary) {
      verticles.put(BlockLibrary.class.getName(), BlockLibrary.address);
    }

    if (fonts) {
      verticles.put(Fonts.class.getName(), Fonts.address);
    }

    if (templates) {
      verticles.put(Templates.class.getName(), Templates.address);
    }

    if (lisp) {
      verticles.put(Lisp.class.getName(), Lisp.address);
    }
  }

  private JsonObject getSecrets() {
    String secretName = parser.getString(ConfigObject.SECRETS, "name_api_secrets", "API_secrets");
    String endpoint = parser.getString(
        ConfigObject.SECRETS, "endpoint_api_secrets", "secretsmanager.us-east-1.amazonaws.com");
    String region = parser.getString(ConfigObject.SECRETS, "region_api_secrets", "us-east-1");

    AwsClientBuilder.EndpointConfiguration config =
        new AwsClientBuilder.EndpointConfiguration(endpoint, region);
    AWSSecretsManagerClientBuilder clientBuilder = AWSSecretsManagerClientBuilder.standard();
    clientBuilder.setEndpointConfiguration(config);
    AWSSecretsManager client = clientBuilder.build();

    GetSecretValueRequest getSecretValueRequest =
        new GetSecretValueRequest().withSecretId(secretName);
    GetSecretValueResult getSecretValueResult = null;
    try {
      getSecretValueResult = client.getSecretValue(getSecretValueRequest);
    } catch (ResourceNotFoundException e) {
      DynamoBusModBase.log.error("The requested secret " + secretName + " was not found");
    } catch (InvalidRequestException e) {
      DynamoBusModBase.log.error("The request was invalid due to: " + e.getMessage());
    } catch (InvalidParameterException e) {
      DynamoBusModBase.log.error("The request had invalid params: " + e.getMessage());
    } catch (Exception e) {
      DynamoBusModBase.log.error("Exception on requesting secrets: " + e.getMessage());
    }

    if (getSecretValueResult == null) {
      return new JsonObject();
    }
    // Decrypted secret using the associated KMS CMK
    // Depending on whether the secret was a string or binary, one of these fields will be populated
    return new JsonObject(getSecretValueResult.getSecretString());
  }

  /**
   * Set general config properties
   *
   * @return Properties
   */
  public Properties build() {
    Properties pro = new Properties();
    final String emptyString = "";
    final String graebertIcon = "https://icons.kudo.graebert.com/areskudo.svg";
    final String graebertIconDark =
        "https://icons.kudo.graebert.com/GraebertDarkStyle/areskudo.svg";

    JsonObject secrets = getSecrets();

    pro.setFluorineSecretKey(secrets.getString("FLUORINE_SECRET_KEY"));
    pro.setFluorineRsaKey(secrets.getString("FLUORINE_RSA_KEY"));
    pro.setLicensingSyncToken(
        parser.getString(ConfigObject.FLUORINE, "licensingSyncToken", emptyString));
    pro.setLicensingApiToken(
        parser.getString(ConfigObject.FLUORINE, "licensingAPIToken", emptyString));
    pro.setCognitoRegion(
        parser.getString(ConfigObject.COGNITO, Field.REGION.getName(), emptyString));
    pro.setCognitoUserPoolId(parser.getString(ConfigObject.COGNITO, "userPoolId", emptyString));
    pro.setCognitoAppClientId(parser.getString(ConfigObject.COGNITO, "appClientId", emptyString));
    pro.setStatsDPrefix(parser.getString(ConfigObject.STATSD, Field.PREFIX.getName(), "localhost"));
    pro.setStatsDKey(
        parser.getString(ConfigObject.STATSD, "key", "d335e1b4-2cdc-443a-89b6-798e554e3e6f"));
    pro.setStatsDHost(parser.getString(
        ConfigObject.STATSD, "host", "statsd-xenon-graebert.elasticbeanstalk.com"));
    pro.setStatsDPort(parser.getInt(ConfigObject.STATSD, "port", 8125));
    pro.setS3Bucket(
        parser.getString(ConfigObject.S3, Field.BUCKET.getName(), "kudo-development-user-data"));
    pro.setS3Region(parser.getString(ConfigObject.S3, Field.REGION.getName(), "us-east-1"));
    pro.setS3BlBucket(parser.getString(ConfigObject.S3, "blBucket", "development-block-library"));
    pro.setS3BlRegion(parser.getString(ConfigObject.S3, "blRegion", "us-east-1"));
    pro.setS3ResourcesBucket(
        parser.getString(ConfigObject.S3, "resourcesBucket", "development-kudo-resources"));
    pro.setTranslateRegion(
        parser.getString(ConfigObject.TRANSLATE, Field.REGION.getName(), "us-east-1"));
    pro.setPrefix(parser.getString(ConfigObject.DYNAMODB, Field.PREFIX.getName(), "kudo_local"));
    pro.setDynamoRegion(
        parser.getString(ConfigObject.DYNAMODB, Field.REGION.getName(), "us-east-1"));
    pro.setDaxEnabled(parser.getBoolean(ConfigObject.DAX, Field.ENABLED.getName(), false));
    pro.setDaxEndpoint(parser.getString(ConfigObject.DAX, "endpoint", emptyString));
    pro.setCfDistribution(
        parser.getString(ConfigObject.THUMBNAIL, Field.BUCKET.getName(), "kudo-thumbnails"));
    pro.setThumbnailExtensions(parser
        .getJsonArray(ConfigObject.THUMBNAIL, "extensions", new JsonArray())
        .toString());
    pro.setThumbnailRevitExtensions(parser
        .getJsonArray(ConfigObject.THUMBNAIL, "revitExtensions", new JsonArray())
        .toString());
    pro.setRevitAdmin(parser.getBoolean(ConfigObject.THUMBNAIL, "isRevitAdmin", true));
    pro.setSqsRegion(parser.getString(ConfigObject.SQS, Field.REGION.getName(), "eu-west-1"));
    pro.setAwsAccountNumber(parser.getString(ConfigObject.SQS, "accnumber", "261943235010"));
    pro.setSqsQueue(parser.getString(ConfigObject.SQS, "thumbnailsQueue", "ThumbnailsQueue"));
    pro.setSqsChunkQueue(
        parser.getString(ConfigObject.SQS, "thumbnailsChunkQueue", "ThumbnailsChunkQueue"));
    pro.setSqsUsersQueue(parser.getString(ConfigObject.SQS, "usersQueue", "TestUsersDeleteQueue"));
    pro.setSqsResponses(
        parser.getString(ConfigObject.SQS, "thumbnailsResponses", "ThumbnailsResponses"));
    pro.setUrl(parser.getString(ConfigObject.FLUORINE, "uiurl", "http://localhost:3000/"));
    pro.setConflictingFileHelpUrl(parser.getString(
        ConfigObject.FLUORINE, "conflictingFileHelpUrl", "https://help.graebert.com"));
    pro.setHost(parser.getString(ConfigObject.FLUORINE, "host", "localhost"));
    pro.setPort(parser.getString(ConfigObject.FLUORINE, "port", "8080"));
    pro.setApikey(parser.getString(ConfigObject.FLUORINE, "apiKey", emptyString));
    pro.setTokenGenerator(parser.getString(ConfigObject.FLUORINE, "tokenGenerator", emptyString));
    pro.setFeedbackEmail(parser.getString(ConfigObject.FLUORINE, "feedback_email", emptyString));

    // storage credentials
    pro.setBoxClientId(secrets.getString("BOX_CLIENT_ID"));
    pro.setBoxClientSecret(secrets.getString("BOX_CLIENT_SECRET"));

    pro.setDropboxAppKey(secrets.getString("DROPBOX_APP_KEY"));
    pro.setDropboxAppSecret(secrets.getString("DROPBOX_APP_SECRET"));
    pro.setDropboxRedirectUrl(parser.getString(
        ConfigObject.DROPBOX, "redirect_uri", "https://oauth.dev.graebert.com/?type=dropbox"));

    pro.setOnedriveClientId(secrets.getString("ONEDRIVE_CLIENT_ID"));
    pro.setOnedriveClientSecret(secrets.getString("ONEDRIVE_CLIENT_SECRET"));
    pro.setOnedriveRedirectUri(
        parser.getString(ConfigObject.ONEDRIVE, "redirect_uri", emptyString));

    pro.setOnedriveBusinessClientId(secrets.getString("ONEDRIVE_BUSINESS_CLIENT_ID"));
    pro.setOnedriveBusinessClientSecret(secrets.getString("ONEDRIVE_BUSINESS_CLIENT_SECRET"));
    pro.setOnedriveBusinessRedirectUri(
        parser.getString(ConfigObject.ONEDRIVE_BUSINESS, "redirect_uri", emptyString));

    pro.setGdriveClientId(secrets.getString("GDRIVE_CLIENT_ID"));
    pro.setGdriveClientSecret(secrets.getString("GDRIVE_CLIENT_SECRET"));
    pro.setGdriveRedirectUri(parser.getString(
        ConfigObject.GDRIVE, "redirect_uri", "https://oauth.dev.graebert.com/?type=google"));
    pro.setGdriveNewClientId(secrets.getString("GDRIVE_NEW_CLIENT_ID"));
    pro.setGdriveNewClientSecret(secrets.getString("GDRIVE_NEW_CLIENT_SECRET"));
    pro.setGdriveNewRedirectUri(parser.getString(
        ConfigObject.GDRIVE, "redirect_uri", "https://oauth.dev.graebert.com/?type=google"));

    pro.setTrimbleClientId(secrets.getString("TRIMBLE_KEY"));
    pro.setTrimbleClientSecret(secrets.getString("TRIMBLE_SECRET"));
    pro.setTrimbleTokenUrl(parser.getString(
        ConfigObject.TRIMBLE, "tokenUrl", "https://stage.id.trimble.com/oauth/token"));
    pro.setTrimbleApiUrl(parser.getString(
        ConfigObject.TRIMBLE, "apiUrl", "https://app.stage.connect.trimble.com/tc/api/2.0"));
    pro.setTrimbleOauthUrl(
        parser.getString(ConfigObject.TRIMBLE, "oauthUrl", "https://stage.id.trimble.com/oauth"));
    pro.setTrimbleRedirectUri(parser.getString(
        ConfigObject.TRIMBLE,
        "redirect_uri",
        "https://oauth.dev.graebert.com/?mode=storage&type=trimble"));

    pro.setOnshapedevClientId(secrets.getString("ONSHAPE_DEMO_C_CLIENT_ID"));
    pro.setOnshapedevClientSecret(secrets.getString("ONSHAPE_DEMO_C_CLIENT_SECRET"));
    pro.setOnshapedevOauthUrl(parser.getString(
        ConfigObject.ONSHAPE_DEV, "oauth_url", "https://demo-c-oauth.dev.onshape.com/oauth/"));
    pro.setOnshapedevApiUrl(parser.getString(
        ConfigObject.ONSHAPE_DEV, "api_url", "https://demo-c.dev.onshape.com/api"));
    pro.setOnshapedevRedirectUri(parser.getString(
        ConfigObject.ONSHAPE_DEV,
        "redirect_uri",
        "https://oauth.dev.graebert.com/?mode=storage&type=onshapedev"));

    pro.setOnshapestagingClientId(secrets.getString("ONSHAPE_STAGING_CLIENT_ID"));
    pro.setOnshapestagingClientSecret(secrets.getString("ONSHAPE_STAGING_CLIENT_SECRET"));
    pro.setOnshapestagingOauthUrl(parser.getString(
        ConfigObject.ONSHAPE_STAGING, "oauth_url", "https://staging-oauth.dev.onshape.com/oauth/"));
    pro.setOnshapestagingApiUrl(parser.getString(
        ConfigObject.ONSHAPE_STAGING, "api_url", "https://staging.dev.onshape.com/api"));
    pro.setOnshapestagingRedirectUri(parser.getString(
        ConfigObject.ONSHAPE_STAGING,
        "redirect_uri",
        "https://oauth.dev.graebert.com/?mode=storage&type=onshapestaging"));

    pro.setOnshapeClientId(secrets.getString("ONSHAPE_CLIENT_ID"));
    pro.setOnshapeClientSecret(secrets.getString("ONSHAPE_CLIENT_SECRET"));
    pro.setOnshapeOauthUrl(
        parser.getString(ConfigObject.ONSHAPE, "oauth_url", "https://oauth.onshape.com/oauth/"));
    pro.setOnshapeApiUrl(
        parser.getString(ConfigObject.ONSHAPE, "api_url", "https://cad.onshape.com/api"));
    pro.setOnshapeRedirectUri(parser.getString(
        ConfigObject.ONSHAPE,
        "redirect_uri",
        "https://oauth.dev.graebert.com/?mode=storage&type=onshape"));

    // Hancom proxy
    pro.setHancomProxyUrl(parser.getString(ConfigObject.HANCOM, "proxy_url", emptyString));
    pro.setHancomProxyPort(parser.getString(ConfigObject.HANCOM, "proxy_port", emptyString));
    pro.setHancomProxyLogin(parser.getString(ConfigObject.HANCOM, "proxy_login", emptyString));
    pro.setHancomProxyPass(parser.getString(ConfigObject.HANCOM, "proxy_pass", emptyString));
    pro.setHancomstgProxyUrl(
        parser.getString(ConfigObject.HANCOM_STAGING, "proxy_url", "108.128.96.42"));
    pro.setHancomstgProxyPort(parser.getString(ConfigObject.HANCOM_STAGING, "proxy_port", "3020"));
    pro.setHancomstgProxyLogin(
        parser.getString(ConfigObject.HANCOM_STAGING, "proxy_login", "graebert"));
    pro.setHancomstgProxyPass(
        parser.getString(ConfigObject.HANCOM_STAGING, "proxy_pass", "graeberTrinity@2020"));

    pro.setSesRegion(parser.getString(ConfigObject.SMTP, Field.REGION.getName(), "us-east-1"));
    pro.setSmtpSender(parser.getString(ConfigObject.SMTP, "sender", "alerts@graebert.com"));
    pro.setSmtpProName(parser.getString(ConfigObject.SMTP, "proName", "ARES Kudo"));
    pro.setSmtpEnableDemo(parser.getBoolean(ConfigObject.SMTP, "enableDemo", false));
    pro.setPostmarkClientId(parser.getString(ConfigObject.POSTMARK, "clientId", emptyString));
    pro.setDevMode(parser.getBoolean(ConfigObject.FLUORINE, "devMode", false));
    pro.setFontsBucket(
        parser.getString(ConfigObject.FONTS, Field.BUCKET.getName(), "kudo-development-fonts"));
    pro.setFontsRegion(parser.getString(ConfigObject.FONTS, Field.REGION.getName(), "us-east-1"));

    // Elasticache
    pro.setElasticacheEndpoint(parser.getString(ConfigObject.ELASTICACHE, "endpoint", "localhost"));
    pro.setElasticachePort(parser.getInt(ConfigObject.ELASTICACHE, "port", 11211));
    pro.setElasticacheEnabled(
        parser.getBoolean(ConfigObject.ELASTICACHE, Field.ENABLED.getName(), false));

    pro.setDefaultUserOptions(parser
        .getJsonObject(
            ConfigObject.DEFAULT_USER_OPTIONS.getLabel(),
            new JsonObject()
                .put(
                    Field.STORAGES.getName(),
                    new JsonObject()
                        .put(ConfigObject.SAMPLES.getLabel(), true)
                        .put(ConfigObject.ONSHAPE.getLabel(), true)
                        .put(ConfigObject.ONSHAPE_DEV.getLabel(), false)
                        .put(ConfigObject.ONSHAPE_STAGING.getLabel(), false)
                        .put(ConfigObject.TRIMBLE.getLabel(), true))
                .put(Field.EDITOR.getName(), true)
                .put(Field.NO_DEBUG_LOG.getName(), true))
        .toString());
    pro.setInstanceOptions(parser
        .getJsonObject(
            ConfigObject.INSTANCE_OPTIONS.getLabel(),
            new JsonObject()
                .put(
                    Field.STORAGES.getName(),
                    new JsonObject()
                        .put(ConfigObject.SAMPLES.getLabel(), true)
                        .put(ConfigObject.ONSHAPE.getLabel(), true)
                        .put(ConfigObject.ONSHAPE_DEV.getLabel(), true)
                        .put(ConfigObject.ONSHAPE_STAGING.getLabel(), true)
                        .put(ConfigObject.TRIMBLE.getLabel(), true))
                .put(Field.EDITOR.getName(), true)
                .put(Field.NO_DEBUG_LOG.getName(), true))
        .toString());
    pro.setInstanceStorages(
        parser.getJsonObject(Field.STORAGES.getName(), new JsonObject()).toString());
    pro.setDefaultCompanyOptions(parser
        .getJsonObject(ConfigObject.DEFAULT_COMPANY_OPTIONS.getLabel(), new JsonObject())
        .toString());
    pro.setUserPreferences(parser
        .getJsonObject(ConfigObject.USER_PREFERENCES.getLabel(), new JsonObject())
        .toString());
    pro.setLicensingUrl(parser.getString(
        ConfigObject.FLUORINE, "licensing", "https://licensing-service-testing.graebert.com/api/"));
    pro.setWebsocketUrl(parser.getString(
        ConfigObject.FLUORINE, "websocket_url", "https://wsnotify.dev.graebert.com/"));
    pro.setWebsocketApikey(parser.getString(
        ConfigObject.FLUORINE, "websocket_apikey", "Dr0XwujFoH7kXyPztlDbG358w5eG1VZf686wwnjD"));
    pro.setWebsocketEnabled(parser.getBoolean(ConfigObject.FLUORINE, "websocket_enabled", true));
    pro.setNotificationsEnabled(
        parser.getBoolean(ConfigObject.FLUORINE, "notifications_enabled", true));
    pro.setOauthUrl(parser.getString(ConfigObject.FLUORINE, "oauth", emptyString));
    pro.setxRayEnabled(parser.getBoolean(ConfigObject.FLUORINE, "xRay", false));
    pro.setEnterprise(parser.getBoolean(ConfigObject.FLUORINE, Field.ENTERPRISE.getName(), false));
    pro.setRevision(parser.getString(ConfigObject.FLUORINE, "revision", "local"));
    pro.setDefaultLocale(parser.getString(ConfigObject.FLUORINE, "default_locale", "en_gb"));

    // kinesis
    pro.setKinesisRegion(
        parser.getString(ConfigObject.KINESIS, Field.REGION.getName(), "us-east-1"));
    pro.setKinesisSessionLogStream(
        parser.getString(ConfigObject.KINESIS, "sessionLogStream", "sessionLogStream"));
    pro.setKinesisEmailLogStream(
        parser.getString(ConfigObject.KINESIS, "emailLogStream", "emailLogStream"));
    pro.setKinesisSharingLogStream(
        parser.getString(ConfigObject.KINESIS, "sharingLogStream", "sharingLogStream"));
    pro.setKinesisSubscriptionLogStream(
        parser.getString(ConfigObject.KINESIS, "subscriptionLogStream", "subscriptionLogStream"));
    pro.setKinesisFilesLogStream(
        parser.getString(ConfigObject.KINESIS, "filesLogStream", "filesLogStream"));
    pro.setKinesisStorageLogStream(
        parser.getString(ConfigObject.KINESIS, "storageLogStream", "storageLogStream"));
    pro.setKinesisBlockLibraryLogStream(parser.getString(
        ConfigObject.KINESIS, "blockLibraryLogStream", "kudo_dev_blocklibraryLogStream"));
    pro.setKinesisMentionLogStream(
        parser.getString(ConfigObject.KINESIS, "mentionLogStream", "kudo_dev_mentionLogStream"));
    pro.setKinesisDataplaneLogStream(parser.getString(
        ConfigObject.KINESIS, "dataplaneLogStream", "kudo_dev_dataplaneLogStream"));
    pro.setKinesisBatchLimit(parser.getInt(ConfigObject.KINESIS, "batchLimit", 10));
    pro.setKinesisFilePath(
        parser.getString(ConfigObject.KINESIS, "filePath", "./src/main/resources/logs/logs.json"));

    // Samples storage
    pro.setSamplesName(
        parser.getString(ConfigObject.SAMPLES, Field.NAME.getName(), "ARES Kudo Drive"));
    pro.setSamplesIcon(parser.getString(ConfigObject.SAMPLES, Field.ICON.getName(), graebertIcon));
    pro.setSamplesIconBlack(
        parser.getString(ConfigObject.SAMPLES, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setSamplesIconPng(
        parser.getString(ConfigObject.SAMPLES, Field.ICON_PNG.getName(), graebertIcon));
    pro.setSamplesIconBlackPng(
        parser.getString(ConfigObject.SAMPLES, Field.ICON_BLACK_PNG.getName(), graebertIconDark));
    pro.setSimpleStorage(parser
        .getJsonObject(
            ConfigObject.SIMPLESTORAGE.getLabel(),
            new JsonObject()
                .put(
                    "default",
                    new JsonObject()
                        .put(Field.REGION.getName(), "us-east-1")
                        .put(Field.BUCKET.getName(), "kudo-development-simple-storage")
                        .put("country", "n. virginia")))
        .toString());

    // Box storage
    pro.setBoxName(parser.getString(ConfigObject.BOX, Field.NAME.getName(), "Box"));
    pro.setBoxIcon(parser.getString(ConfigObject.BOX, Field.ICON.getName(), graebertIcon));
    pro.setBoxIconBlack(
        parser.getString(ConfigObject.BOX, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setBoxIconPng(parser.getString(ConfigObject.BOX, Field.ICON_PNG.getName(), graebertIcon));
    pro.setBoxIconBlackPng(
        parser.getString(ConfigObject.BOX, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // GDrive storage
    pro.setGdriveName(parser.getString(ConfigObject.GDRIVE, Field.NAME.getName(), "Google Drive"));
    pro.setGdriveIcon(parser.getString(ConfigObject.GDRIVE, Field.ICON.getName(), graebertIcon));
    pro.setGdriveIconBlack(
        parser.getString(ConfigObject.GDRIVE, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setGdriveIconPng(
        parser.getString(ConfigObject.GDRIVE, Field.ICON_PNG.getName(), graebertIcon));
    pro.setGdriveIconBlackPng(
        parser.getString(ConfigObject.GDRIVE, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Dropbox storage
    pro.setDropboxName(parser.getString(ConfigObject.DROPBOX, Field.NAME.getName(), "Dropbox"));
    pro.setDropboxIcon(parser.getString(ConfigObject.DROPBOX, Field.ICON.getName(), graebertIcon));
    pro.setDropboxIconBlack(
        parser.getString(ConfigObject.DROPBOX, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setDropboxIconPng(
        parser.getString(ConfigObject.DROPBOX, Field.ICON_PNG.getName(), graebertIcon));
    pro.setDropboxIconBlackPng(
        parser.getString(ConfigObject.DROPBOX, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Onedrive storage
    pro.setOnedriveName(parser.getString(ConfigObject.ONEDRIVE, Field.NAME.getName(), "oneDrive"));
    pro.setOnedriveIcon(
        parser.getString(ConfigObject.ONEDRIVE, Field.ICON.getName(), graebertIcon));
    pro.setOnedriveIconBlack(
        parser.getString(ConfigObject.ONEDRIVE, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setOnedriveIconPng(
        parser.getString(ConfigObject.ONEDRIVE, Field.ICON_PNG.getName(), graebertIcon));
    pro.setOnedriveIconBlackPng(
        parser.getString(ConfigObject.ONEDRIVE, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Sharepoint storage
    pro.setSharepointName(
        parser.getString(ConfigObject.SHAREPOINT, Field.NAME.getName(), "oneDrive"));
    pro.setSharepointIcon(
        parser.getString(ConfigObject.SHAREPOINT, Field.ICON.getName(), graebertIcon));
    pro.setSharepointIconBlack(
        parser.getString(ConfigObject.SHAREPOINT, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setSharepointIconPng(
        parser.getString(ConfigObject.SHAREPOINT, Field.ICON_PNG.getName(), graebertIcon));
    pro.setSharepointIconBlackPng(parser.getString(
        ConfigObject.SHAREPOINT, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Trimble Connect storage
    pro.setTrimbleName(
        parser.getString(ConfigObject.TRIMBLE, Field.NAME.getName(), "Trimble Connect"));
    pro.setTrimbleIcon(parser.getString(ConfigObject.TRIMBLE, Field.ICON.getName(), graebertIcon));
    pro.setTrimbleIconBlack(
        parser.getString(ConfigObject.TRIMBLE, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setTrimbleIconPng(
        parser.getString(ConfigObject.TRIMBLE, Field.ICON_PNG.getName(), graebertIcon));
    pro.setTrimbleIconBlackPng(
        parser.getString(ConfigObject.TRIMBLE, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Webdav storage
    pro.setWebdavName(parser.getString(ConfigObject.WEBDAV, Field.NAME.getName(), emptyString));
    pro.setWebdavIcon(parser.getString(ConfigObject.WEBDAV, Field.ICON.getName(), emptyString));
    pro.setWebdavIconBlack(
        parser.getString(ConfigObject.WEBDAV, Field.ICON_BLACK.getName(), emptyString));
    pro.setWebdavIconPng(
        parser.getString(ConfigObject.WEBDAV, Field.ICON_PNG.getName(), emptyString));
    pro.setWebdavIconBlackPng(
        parser.getString(ConfigObject.WEBDAV, Field.ICON_BLACK_PNG.getName(), emptyString));

    // Nextcloud storage
    pro.setNextcloudName(
        parser.getString(ConfigObject.NEXTCLOUD, Field.NAME.getName(), emptyString));
    pro.setNextcloudIcon(
        parser.getString(ConfigObject.NEXTCLOUD, Field.ICON.getName(), emptyString));
    pro.setNextcloudIconBlack(
        parser.getString(ConfigObject.NEXTCLOUD, Field.ICON_BLACK.getName(), emptyString));
    pro.setNextcloudIconPng(
        parser.getString(ConfigObject.NEXTCLOUD, Field.ICON_PNG.getName(), emptyString));
    pro.setNextcloudIconBlackPng(
        parser.getString(ConfigObject.NEXTCLOUD, Field.ICON_BLACK_PNG.getName(), emptyString));

    // Onshape storage
    pro.setOnshapeName(parser.getString(ConfigObject.ONSHAPE, Field.NAME.getName(), "Onshape"));
    pro.setOnshapeIcon(parser.getString(ConfigObject.ONSHAPE, Field.ICON.getName(), graebertIcon));
    pro.setOnshapeIconBlack(
        parser.getString(ConfigObject.ONSHAPE, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setOnshapeIconPng(
        parser.getString(ConfigObject.ONSHAPE, Field.ICON_PNG.getName(), graebertIcon));
    pro.setOnshapeIconBlackPng(
        parser.getString(ConfigObject.ONSHAPE, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Onshape-Dev storage
    pro.setOnshapedevName(
        parser.getString(ConfigObject.ONSHAPE_DEV, Field.NAME.getName(), "Onshape Dev"));
    pro.setOnshapedevIcon(
        parser.getString(ConfigObject.ONSHAPE_DEV, Field.ICON.getName(), graebertIcon));
    pro.setOnshapedevIconBlack(
        parser.getString(ConfigObject.ONSHAPE_DEV, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setOnshapedevIconPng(
        parser.getString(ConfigObject.ONSHAPE_DEV, Field.ICON_PNG.getName(), graebertIcon));
    pro.setOnshapedevIconBlackPng(parser.getString(
        ConfigObject.ONSHAPE_DEV, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Onshape-Staging storage
    pro.setOnshapestagingName(
        parser.getString(ConfigObject.ONSHAPE_STAGING, Field.NAME.getName(), "Onshape Staging"));
    pro.setOnshapestagingIcon(
        parser.getString(ConfigObject.ONSHAPE_STAGING, Field.ICON.getName(), graebertIcon));
    pro.setOnshapestagingIconBlack(parser.getString(
        ConfigObject.ONSHAPE_STAGING, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setOnshapestagingIconPng(
        parser.getString(ConfigObject.ONSHAPE_STAGING, Field.ICON_PNG.getName(), graebertIcon));
    pro.setOnshapestagingIconBlackPng(parser.getString(
        ConfigObject.ONSHAPE_STAGING, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Hancom storage
    pro.setHancomName(parser.getString(
        ConfigObject.HANCOM, Field.NAME.getName(), ConfigObject.HANCOM.getLabel()));
    pro.setHancomIcon(parser.getString(ConfigObject.HANCOM, Field.ICON.getName(), graebertIcon));
    pro.setHancomIconBlack(
        parser.getString(ConfigObject.HANCOM, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setHancomIconPng(
        parser.getString(ConfigObject.HANCOM, Field.ICON_PNG.getName(), graebertIcon));
    pro.setHancomIconBlackPng(
        parser.getString(ConfigObject.HANCOM, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    // Hancom-staging storage
    pro.setHancomstgName(parser.getString(
        ConfigObject.HANCOM_STAGING, Field.NAME.getName(), ConfigObject.HANCOM.getLabel()));
    pro.setHancomstgIcon(
        parser.getString(ConfigObject.HANCOM_STAGING, Field.ICON.getName(), graebertIcon));
    pro.setHancomstgIconBlack(parser.getString(
        ConfigObject.HANCOM_STAGING, Field.ICON_BLACK.getName(), graebertIconDark));
    pro.setHancomstgIconPng(
        parser.getString(ConfigObject.HANCOM_STAGING, Field.ICON_PNG.getName(), graebertIcon));
    pro.setHancomstgIconBlackPng(parser.getString(
        ConfigObject.HANCOM_STAGING, Field.ICON_BLACK_PNG.getName(), graebertIconDark));

    pro.setSamlCheckEntitlement(parser.getBoolean(ConfigObject.SAML, "check_entitlement", true));
    pro.setSamlEntitlement(parser.getString(ConfigObject.SAML, "entitlement", emptyString));
    pro.setSamlCheckDomain(parser.getBoolean(ConfigObject.SAML, "check_domain", true));
    pro.setSamlWhitelist(
        parser.getJsonArray(ConfigObject.SAML, "whitelist", new JsonArray()).toString());
    pro.setMaxUploadSize(parser.getInt(ConfigObject.FLUORINE, "maxUploadSize", 50));
    pro.setSecurityHeaderSchema(
        parser.getString(ConfigObject.FLUORINE, "securityHeaderSchema", "sessionHeaderAuth"));
    pro.setSecurityCookieSchema(
        parser.getString(ConfigObject.FLUORINE, "securityCookieSchema", "sessionCookieAuth"));
    pro.setNewSessionWorkflow(
        parser.getBoolean(ConfigObject.FLUORINE, Field.NEW_SESSION_WORKFLOW.getName(), false));
    pro.setMaxUserSessions(parser.getInt(ConfigObject.FLUORINE, "maxUserSessions", 5));
    pro.setXKudoSecret(secrets.getString("KUDO_LICENSING_VERIFY_SECRET"));
    pro.setSamplesFiles(parser
        .getJsonObject(ConfigObject.SAMPLE_FILES.getLabel(), new JsonObject())
        .toString());
    pro.setCheckExportCompliance(
        parser.getBoolean(ConfigObject.FLUORINE, "checkExportCompliance", false));
    pro.setShortenServiceURL(parser.getString(ConfigObject.FLUORINE, "shortenServiceURL", null));
    pro.setReturnDownloadUrl(parser.getBoolean(ConfigObject.FLUORINE, "returnDownloadUrl", false));

    // thumbnail chunk size
    pro.setOnedriveThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.ONEDRIVE.getLabel(), 10));
    pro.setOnedriveBusinessThumbnailChunkSize(parser.getInt(
        ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.ONEDRIVEBUSINESS.getLabel(), 10));
    pro.setBoxThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.BOX.getLabel(), 10));
    pro.setDropboxThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.DROPBOX.getLabel(), 10));
    pro.setGdriveThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.GDRIVE.getLabel(), 10));
    pro.setSharepointThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.SHAREPOINT.getLabel(), 10));
    pro.setOnshapeThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.ONSHAPE.getLabel(), 10));
    pro.setOnshapedevThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.ONSHAPE_DEV.getLabel(), 10));
    pro.setOnshapestagingThumbnailChunkSize(parser.getInt(
        ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.ONSHAPE_STAGING.getLabel(), 10));
    pro.setSamplesThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.SAMPLES.getLabel(), 10));
    pro.setHancomThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.HANCOM.getLabel(), 10));
    pro.setHancomstgThumbnailChunkSize(parser.getInt(
        ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.HANCOM_STAGING.getLabel(), 10));
    pro.setWebdavThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.WEBDAV.getLabel(), 5));
    pro.setNextcloudThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.NEXTCLOUD.getLabel(), 5));
    pro.setTrimbleThumbnailChunkSize(
        parser.getInt(ConfigObject.THUMBNAIL_CHUNK_SIZE, ConfigObject.TRIMBLE.getLabel(), 10));

    // supported files
    pro.setBlocksSupportedFileTypes(parser
        .getJsonArray(
            ConfigObject.RESOURCES,
            ResourceType.BLOCKS.name(),
            Field.SUPPORTED_FILE_TYPES.getName(),
            new JsonArray(Lists.newArrayList("dwg", "dwt", "dxf")))
        .toString());
    pro.setTemplatesSupportedFileTypes(parser
        .getJsonArray(
            ConfigObject.RESOURCES,
            ResourceType.TEMPLATES.name(),
            Field.SUPPORTED_FILE_TYPES.getName(),
            new JsonArray(Lists.newArrayList("dwg", "dwt", "dxf")))
        .toString());
    pro.setFontsSupportedFileTypes(parser
        .getJsonArray(
            ConfigObject.RESOURCES,
            ResourceType.FONTS.name(),
            Field.SUPPORTED_FILE_TYPES.getName(),
            new JsonArray(Lists.newArrayList("ttf", "shx")))
        .toString());
    pro.setLispSupportedFileTypes(parser
        .getJsonArray(
            ConfigObject.RESOURCES,
            ResourceType.LISP.name(),
            Field.SUPPORTED_FILE_TYPES.getName(),
            new JsonArray(Lists.newArrayList("lsp")))
        .toString());
    pro.setScanUsersList(parser.getBoolean(ConfigObject.FLUORINE, "scanUsersList", true));

    pro.setHancom(parser.getBoolean(ConfigObject.STORAGES, "HANCOM", true));
    pro.setHancomstg(parser.getBoolean(ConfigObject.STORAGES, "HANCOMSTG", true));
    pro.setOnedrive(parser.getBoolean(ConfigObject.STORAGES, "ONEDRIVE", true));
    pro.setOnshape(parser.getBoolean(ConfigObject.STORAGES, "ONSHAPE", true));
    pro.setOnshapedev(parser.getBoolean(ConfigObject.STORAGES, "ONSHAPEDEV", true));
    pro.setOnshapestaging(parser.getBoolean(ConfigObject.STORAGES, "ONSHAPESTAGING", true));
    pro.setOnedrivebusiness(false);

    return pro;
  }

  /**
   * Set config properties for StorageVerticle class
   *
   * @param domain - to set domain property
   * @return Properties
   */
  public CustomProperties buildForStorageVerticle(String domain) {
    CustomProperties pro = new CustomProperties();

    pro.setServerPort(parser.getInt(ConfigObject.FLUORINE, "port", 8080));
    pro.setHost(parser.getString(ConfigObject.FLUORINE, "host", "0.0.0.0"));
    pro.setNewSessionWorkflow(
        parser.getBoolean(ConfigObject.FLUORINE, Field.NEW_SESSION_WORKFLOW.getName(), false));
    pro.setMaxUploadSize(parser.getInt(ConfigObject.FLUORINE, "maxUploadSize", 50));
    pro.setSecurityHeaderSchema(
        parser.getString(ConfigObject.FLUORINE, "securityHeaderSchema", "sessionHeaderAuth"));
    pro.setSecurityCookieSchema(
        parser.getString(ConfigObject.FLUORINE, "securityCookieSchema", "sessionCookieAuth"));
    pro.setRateLimit(parser.getInt(ConfigObject.FLUORINE, "rateLimit", 80));
    pro.setRateLimiterEnabled(parser.getBoolean(ConfigObject.FLUORINE, "rateLimiterEnabled", true));
    pro.setMountApi(parser.getBoolean(ConfigObject.FLUORINE, "mount_api", false));
    pro.setDomain(domain);
    pro.setRevision(parser.getString(ConfigObject.FLUORINE, "revision", "local"));
    pro.setLicensing(parser.getString(
        ConfigObject.FLUORINE, "licensing", "https://licensing-service-testing.graebert.com/api/"));
    pro.setUiUrl(parser.getString(ConfigObject.FLUORINE, "uiurl", "http://localhost:3000/"));
    pro.setEnterprise(parser.getBoolean(ConfigObject.FLUORINE, Field.ENTERPRISE.getName(), false));
    pro.setDownloadRootFolders(parser
        .getJsonObject(ConfigObject.DOWNLOAD_ROOT_FOLDERS.getLabel(), new JsonObject())
        .toString());
    pro.setDynamoDBPrefix(
        parser.getString(ConfigObject.DYNAMODB, Field.PREFIX.getName(), "kudo_local"));

    return pro;
  }
}
