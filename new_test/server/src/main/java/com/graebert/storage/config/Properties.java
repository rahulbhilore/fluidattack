package com.graebert.storage.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

public class Properties {
  private String fluorineSecretKey;
  private String fluorineRsaKey;
  private String licensingSyncToken;
  private String licensingApiToken;
  private String cognitoRegion;
  private String cognitoUserPoolId;
  private String cognitoAppClientId;
  private String statsDPrefix;
  private String statsDKey;
  private String statsDHost;
  private int statsDPort;
  private String s3Bucket;
  private String s3Region;
  private String s3BlBucket;
  private String s3BlRegion;
  private String s3ResourcesBucket;
  private String translateRegion;
  private String prefix;
  private String dynamoRegion;
  private Boolean daxEnabled;
  private String daxEndpoint;
  private String cfDistribution;
  private String thumbnailExtensions;
  private String thumbnailRevitExtensions;
  private Boolean revitAdmin;
  private String sqsRegion;
  private String awsAccountNumber;
  private String sqsQueue;
  private String sqsChunkQueue;
  private String sqsUsersQueue;
  private String sqsResponses;
  private String conflictingFileHelpUrl;
  private String host;
  private String port;
  private String apikey;
  private String tokenGenerator;
  private String feedbackEmail;
  private String boxClientId;
  private String boxClientSecret;
  private String dropboxAppKey;
  private String dropboxAppSecret;
  private String dropboxRedirectUrl;
  private Boolean onedrive;
  private String onedriveClientId;
  private String onedriveClientSecret;
  private String onedriveRedirectUri;
  private Boolean onedrivebusiness;
  private String onedriveBusinessClientId;
  private String onedriveBusinessClientSecret;
  private String onedriveBusinessRedirectUri;
  private String gdriveClientId;
  private String gdriveClientSecret;
  private String gdriveRedirectUri;
  private String gdriveNewClientId;
  private String gdriveNewClientSecret;
  private String gdriveNewRedirectUri;
  private String trimbleClientId;
  private String trimbleClientSecret;
  private String trimbleTokenUrl;
  private String trimbleApiUrl;
  private String trimbleOauthUrl;
  private String trimbleRedirectUri;
  private Boolean onshapedev;
  private String onshapedevClientId;
  private String onshapedevClientSecret;
  private String onshapedevOauthUrl;
  private String onshapedevApiUrl;
  private String onshapedevRedirectUri;
  private Boolean onshapestaging;
  private String onshapestagingClientId;
  private String onshapestagingClientSecret;
  private String onshapestagingOauthUrl;
  private String onshapestagingApiUrl;
  private String onshapestagingRedirectUri;
  private Boolean onshape;
  private String onshapeClientId;
  private String onshapeClientSecret;
  private String onshapeOauthUrl;
  private String onshapeApiUrl;
  private String onshapeRedirectUri;
  private Boolean hancom;
  private String hancomProxyUrl;
  private String hancomProxyPort;
  private String hancomProxyLogin;
  private String hancomProxyPass;
  private Boolean hancomstg;
  private String hancomstgProxyUrl;
  private String hancomstgProxyPort;
  private String hancomstgProxyLogin;
  private String hancomstgProxyPass;
  private String sesRegion;
  private String smtpSender;
  private String smtpProName;
  private Boolean smtpEnableDemo;
  private String postmarkClientId;
  private Boolean devMode;
  private String elasticacheEndpoint;
  private int elasticachePort;
  private Boolean elasticacheEnabled;
  private String defaultUserOptions;
  private String instanceOptions;
  private String instanceStorages;
  private String defaultCompanyOptions;
  private String userPreferences;
  private String licensingUrl;
  private String websocketUrl;
  private String websocketApikey;
  private Boolean websocketEnabled;
  private Boolean notificationsEnabled;
  private String oauthUrl;
  private Boolean xRayEnabled;
  private Boolean enterprise;
  private String revision;
  private String defaultLocale;
  private String samplesName;
  private String samplesIcon;
  private String samplesIconBlack;
  private String samplesIconPng;
  private String samplesIconBlackPng;
  private String simpleStorage;
  private String kinesisRegion;
  private String kinesisSessionLogStream;
  private String kinesisEmailLogStream;
  private String kinesisSharingLogStream;
  private String kinesisSubscriptionLogStream;
  private String kinesisFilesLogStream;
  private String kinesisStorageLogStream;
  private String kinesisBlockLibraryLogStream;
  private String kinesisMentionLogStream;
  private String kinesisDataplaneLogStream;
  private int kinesisBatchLimit;
  private String kinesisFilePath;
  private String fontsBucket;
  private String fontsRegion;
  private String boxName;
  private String boxIcon;
  private String boxIconBlack;
  private String boxIconPng;
  private String boxIconBlackPng;
  private String gdriveName;
  private String gdriveIcon;
  private String gdriveIconBlack;
  private String gdriveIconPng;
  private String gdriveIconBlackPng;
  private String dropboxName;
  private String dropboxIcon;
  private String dropboxIconBlack;
  private String dropboxIconPng;
  private String dropboxIconBlackPng;
  private String onedriveName;
  private String onedriveIcon;
  private String onedriveIconBlack;
  private String onedriveIconPng;
  private String onedriveIconBlackPng;
  private String sharepointName;
  private String sharepointIcon;
  private String sharepointIconBlack;
  private String sharepointIconPng;
  private String sharepointIconBlackPng;
  private String trimbleName;
  private String trimbleIcon;
  private String trimbleIconBlack;
  private String trimbleIconPng;
  private String trimbleIconBlackPng;
  private String webdavName;
  private String webdavIcon;
  private String webdavIconBlack;
  private String webdavIconPng;
  private String webdavIconBlackPng;
  private String nextcloudName;
  private String nextcloudIcon;
  private String nextcloudIconBlack;
  private String nextcloudIconPng;
  private String nextcloudIconBlackPng;
  private String onshapestagingName;
  private String onshapestagingIcon;
  private String onshapestagingIconBlack;
  private String onshapestagingIconPng;
  private String onshapestagingIconBlackPng;
  private String onshapedevName;
  private String onshapedevIcon;
  private String onshapedevIconBlack;
  private String onshapedevIconPng;
  private String onshapedevIconBlackPng;
  private String onshapeName;
  private String onshapeIcon;
  private String onshapeIconBlack;
  private String onshapeIconPng;
  private String onshapeIconBlackPng;
  private String hancomName;
  private String hancomIcon;
  private String hancomIconBlack;
  private String hancomIconPng;
  private String hancomIconBlackPng;
  private String hancomstgName;
  private String hancomstgIcon;
  private String hancomstgIconBlack;
  private String hancomstgIconPng;
  private String hancomstgIconBlackPng;
  private Boolean samlCheckEntitlement;
  private String samlEntitlement;
  private Boolean samlCheckDomain;
  private String samlWhitelist;
  private int maxUploadSize;
  private String securityHeaderSchema;
  private String securityCookieSchema;
  private Boolean newSessionWorkflow;
  private int maxUserSessions;
  private String xKudoSecret;
  private String samplesFiles;
  private Boolean checkExportCompliance;
  private String shortenServiceURL;
  private Boolean returnDownloadUrl;
  private int onedriveThumbnailChunkSize;
  private int onedriveBusinessThumbnailChunkSize;
  private int sharepointThumbnailChunkSize;
  private int boxThumbnailChunkSize;
  private int gdriveThumbnailChunkSize;
  private int dropboxThumbnailChunkSize;
  private int onshapeThumbnailChunkSize;
  private int onshapedevThumbnailChunkSize;
  private int onshapestagingThumbnailChunkSize;
  private int samplesThumbnailChunkSize;
  private int hancomThumbnailChunkSize;
  private int hancomstgThumbnailChunkSize;
  private int webdavThumbnailChunkSize;
  private int nextcloudThumbnailChunkSize;
  private int trimbleThumbnailChunkSize;
  private String blocksSupportedFileTypes;
  private String templatesSupportedFileTypes;
  private String fontsSupportedFileTypes;
  private String lispSupportedFileTypes;
  private String url;
  private Boolean scanUsersList;

  private final String emptyString = "";

  public String getFluorineSecretKey() {
    return (String) getPropertyValue(fluorineSecretKey);
  }

  public void setFluorineSecretKey(String fluorineSecretKey) {
    this.fluorineSecretKey = fluorineSecretKey;
  }

  public String getFluorineRsaKey() {
    return (String) getPropertyValue(fluorineRsaKey);
  }

  public void setFluorineRsaKey(String fluorineRsaKey) {
    this.fluorineRsaKey = fluorineRsaKey;
  }

  public String getLicensingSyncToken() {
    return (String) getPropertyValue(true, null, licensingSyncToken);
  }

  public void setLicensingSyncToken(String licensingSyncToken) {
    this.licensingSyncToken = licensingSyncToken;
  }

  public String getLicensingApiToken() {
    return (String) getPropertyValue(true, null, licensingApiToken);
  }

  public void setLicensingApiToken(String licensingApiToken) {
    this.licensingApiToken = licensingApiToken;
  }

  public String getCognitoRegion() {
    return (String) getPropertyValue(cognitoRegion);
  }

  public void setCognitoRegion(String cognitoRegion) {
    this.cognitoRegion = cognitoRegion;
  }

  public String getCognitoUserPoolId() {
    return (String) getPropertyValue(cognitoUserPoolId);
  }

  public void setCognitoUserPoolId(String cognitoUserPoolId) {
    this.cognitoUserPoolId = cognitoUserPoolId;
  }

  public String getCognitoAppClientId() {
    return (String) getPropertyValue(cognitoAppClientId);
  }

  public void setCognitoAppClientId(String cognitoAppClientId) {
    this.cognitoAppClientId = cognitoAppClientId;
  }

  public String getStatsDPrefix() {
    return (String) getPropertyValue(statsDPrefix);
  }

  public void setStatsDPrefix(String statsDPrefix) {
    this.statsDPrefix = statsDPrefix;
  }

  public String getStatsDKey() {
    return (String) getPropertyValue(statsDKey);
  }

  public void setStatsDKey(String statsDKey) {
    this.statsDKey = statsDKey;
  }

  public String getStatsDHost() {
    return (String) getPropertyValue(statsDHost);
  }

  public void setStatsDHost(String statsDHost) {
    this.statsDHost = statsDHost;
  }

  public int getStatsDPort() {
    return (int) getPropertyValue(statsDPort);
  }

  public void setStatsDPort(int statsDPort) {
    this.statsDPort = statsDPort;
  }

  public String getS3Bucket() {
    return (String) getPropertyValue(s3Bucket);
  }

  public void setS3Bucket(String s3Bucket) {
    this.s3Bucket = s3Bucket;
  }

  public String getS3Region() {
    return (String) getPropertyValue(s3Region);
  }

  public void setS3Region(String s3Region) {
    this.s3Region = s3Region;
  }

  public String getS3BlBucket() {
    return (String) getPropertyValue(s3BlBucket);
  }

  public void setS3BlBucket(String s3BlBucket) {
    this.s3BlBucket = s3BlBucket;
  }

  public String getS3BlRegion() {
    return (String) getPropertyValue(s3BlRegion);
  }

  public void setS3BlRegion(String s3BlRegion) {
    this.s3BlRegion = s3BlRegion;
  }

  public String getS3ResourcesBucket() {
    return (String) getPropertyValue(s3ResourcesBucket);
  }

  public void setS3ResourcesBucket(String s3ResourcesBucket) {
    this.s3ResourcesBucket = s3ResourcesBucket;
  }

  public String getTranslateRegion() {
    return (String) getPropertyValue(translateRegion);
  }

  public void setTranslateRegion(String translateRegion) {
    this.translateRegion = translateRegion;
  }

  public String getPrefix() {
    return (String) getPropertyValue(prefix);
  }

  public void setPrefix(String prefix) {
    this.prefix = prefix;
  }

  public String getDynamoRegion() {
    return (String) getPropertyValue(dynamoRegion);
  }

  public void setDynamoRegion(String dynamoRegion) {
    this.dynamoRegion = dynamoRegion;
  }

  public Boolean getDaxEnabled() {
    return (Boolean) getPropertyValue(daxEnabled);
  }

  public void setDaxEnabled(Boolean daxEnabled) {
    this.daxEnabled = daxEnabled;
  }

  public String getDaxEndpoint() {
    return (String) getPropertyValue(daxEndpoint);
  }

  public void setDaxEndpoint(String daxEndpoint) {
    this.daxEndpoint = daxEndpoint;
  }

  public String getCfDistribution() {
    return (String) getPropertyValue(cfDistribution);
  }

  public void setCfDistribution(String cfDistribution) {
    this.cfDistribution = cfDistribution;
  }

  public String getThumbnailExtensions() {
    return (String) getPropertyValue(thumbnailExtensions);
  }

  public JsonArray getThumbnailExtensionsAsArray() {
    String arrayAsString = getThumbnailExtensions();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setThumbnailExtensions(String thumbnailExtensions) {
    this.thumbnailExtensions = thumbnailExtensions;
  }

  public String getThumbnailRevitExtensions() {
    return (String) getPropertyValue(thumbnailRevitExtensions);
  }

  public JsonArray getThumbnailRevitExtensionsAsArray() {
    String arrayAsString = getThumbnailRevitExtensions();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setThumbnailRevitExtensions(String thumbnailRevitExtensions) {
    this.thumbnailRevitExtensions = thumbnailRevitExtensions;
  }

  public Boolean getRevitAdmin() {
    return (Boolean) getPropertyValue(revitAdmin);
  }

  public void setRevitAdmin(Boolean revitAdmin) {
    this.revitAdmin = revitAdmin;
  }

  public String getSqsRegion() {
    return (String) getPropertyValue(sqsRegion);
  }

  public void setSqsRegion(String sqsRegion) {
    this.sqsRegion = sqsRegion;
  }

  public String getAwsAccountNumber() {
    return (String) getPropertyValue(awsAccountNumber);
  }

  public void setAwsAccountNumber(String awsAccountNumber) {
    this.awsAccountNumber = awsAccountNumber;
  }

  public String getSqsQueue() {
    return (String) getPropertyValue(sqsQueue);
  }

  public void setSqsQueue(String sqsQueue) {
    this.sqsQueue = sqsQueue;
  }

  public String getSqsChunkQueue() {
    return (String) getPropertyValue(sqsChunkQueue);
  }

  public void setSqsChunkQueue(String sqsChunkQueue) {
    this.sqsChunkQueue = sqsChunkQueue;
  }

  public String getSqsUsersQueue() {
    return (String) getPropertyValue(sqsUsersQueue);
  }

  public void setSqsUsersQueue(String sqsUsersQueue) {
    this.sqsUsersQueue = sqsUsersQueue;
  }

  public String getSqsResponses() {
    return (String) getPropertyValue(sqsResponses);
  }

  public void setSqsResponses(String sqsResponses) {
    this.sqsResponses = sqsResponses;
  }

  public String getConflictingFileHelpUrl() {
    return (String) getPropertyValue(conflictingFileHelpUrl);
  }

  public void setConflictingFileHelpUrl(String conflictingFileHelpUrl) {
    this.conflictingFileHelpUrl = conflictingFileHelpUrl;
  }

  public String getHost() {
    return (String) getPropertyValue(host);
  }

  public void setHost(String host) {
    this.host = host;
  }

  public String getPort() {
    return (String) getPropertyValue(port);
  }

  public void setPort(String port) {
    this.port = port;
  }

  public String getApikey() {
    return (String) getPropertyValue(apikey);
  }

  public void setApikey(String apikey) {
    this.apikey = apikey;
  }

  public String getTokenGenerator() {
    return (String) getPropertyValue(tokenGenerator);
  }

  public void setTokenGenerator(String tokenGenerator) {
    this.tokenGenerator = tokenGenerator;
  }

  public String getFeedbackEmail() {
    return (String) getPropertyValue(true, emptyString, feedbackEmail);
  }

  public void setFeedbackEmail(String feedbackEmail) {
    this.feedbackEmail = feedbackEmail;
  }

  public String getBoxClientId() {
    return (String) getPropertyValue(true, "gyvfy3gkpyeib2oyxsh1eu59348xoeec", boxClientId);
  }

  public void setBoxClientId(String boxClientId) {
    this.boxClientId = boxClientId;
  }

  public String getBoxClientSecret() {
    return (String) getPropertyValue(true, "jQNm8bjM5y5O9qhun2Hyhs1U1vWARtgk", boxClientSecret);
  }

  public void setBoxClientSecret(String boxClientSecret) {
    this.boxClientSecret = boxClientSecret;
  }

  public String getDropboxAppKey() {
    return (String) getPropertyValue(true, "dfnr5r6dtm2ioi3", dropboxAppKey);
  }

  public void setDropboxAppKey(String dropboxAppKey) {
    this.dropboxAppKey = dropboxAppKey;
  }

  public String getDropboxAppSecret() {
    return (String) getPropertyValue(true, "p0w5fql3t9zv59f", dropboxAppSecret);
  }

  public void setDropboxAppSecret(String dropboxAppSecret) {
    this.dropboxAppSecret = dropboxAppSecret;
  }

  public String getDropboxRedirectUrl() {
    return (String) getPropertyValue(true, emptyString, dropboxRedirectUrl);
  }

  public void setDropboxRedirectUrl(String dropboxRedirectUrl) {
    this.dropboxRedirectUrl = dropboxRedirectUrl;
  }

  public String getOnedriveClientId() {
    return (String) getPropertyValue(onedriveClientId);
  }

  public void setOnedriveClientId(String onedriveClientId) {
    this.onedriveClientId = onedriveClientId;
  }

  public String getOnedriveClientSecret() {
    return (String) getPropertyValue(onedriveClientSecret);
  }

  public void setOnedriveClientSecret(String onedriveClientSecret) {
    this.onedriveClientSecret = onedriveClientSecret;
  }

  public String getOnedriveRedirectUri() {
    return (String) getPropertyValue(onedriveRedirectUri);
  }

  public void setOnedriveRedirectUri(String onedriveRedirectUri) {
    this.onedriveRedirectUri = onedriveRedirectUri;
  }

  public Boolean getOnedrivebusiness() {
    return (Boolean) getPropertyValue(onedrivebusiness);
  }

  public void setOnedrivebusiness(Boolean onedrivebusiness) {
    this.onedrivebusiness = onedrivebusiness;
  }

  public Boolean getHancomstg() {
    return (Boolean) getPropertyValue(hancomstg);
  }

  public void setHancomstg(Boolean hancomstg) {
    this.hancomstg = hancomstg;
  }

  public String getOnedriveBusinessClientId() {
    return (String) getPropertyValue(onedriveBusinessClientId);
  }

  public void setOnedriveBusinessClientId(String onedriveBusinessClientId) {
    this.onedriveBusinessClientId = onedriveBusinessClientId;
  }

  public String getOnedriveBusinessClientSecret() {
    return (String) getPropertyValue(onedriveBusinessClientSecret);
  }

  public void setOnedriveBusinessClientSecret(String onedriveBusinessClientSecret) {
    this.onedriveBusinessClientSecret = onedriveBusinessClientSecret;
  }

  public String getOnedriveBusinessRedirectUri() {
    return (String) getPropertyValue(onedriveBusinessRedirectUri);
  }

  public void setOnedriveBusinessRedirectUri(String onedriveBusinessRedirectUri) {
    this.onedriveBusinessRedirectUri = onedriveBusinessRedirectUri;
  }

  public String getGdriveClientId() {
    return (String) getPropertyValue(true, emptyString, gdriveClientId);
  }

  public void setGdriveClientId(String gdriveClientId) {
    this.gdriveClientId = gdriveClientId;
  }

  public String getGdriveClientSecret() {
    return (String) getPropertyValue(true, emptyString, gdriveClientSecret);
  }

  public void setGdriveClientSecret(String gdriveClientSecret) {
    this.gdriveClientSecret = gdriveClientSecret;
  }

  public String getGdriveRedirectUri() {
    return (String) getPropertyValue(true, emptyString, gdriveRedirectUri);
  }

  public void setGdriveRedirectUri(String gdriveRedirectUri) {
    this.gdriveRedirectUri = gdriveRedirectUri;
  }

  public String getGdriveNewClientId() {
    return (String) getPropertyValue(true, emptyString, gdriveNewClientId);
  }

  public void setGdriveNewClientId(String gdriveNewClientId) {
    this.gdriveNewClientId = gdriveNewClientId;
  }

  public String getGdriveNewClientSecret() {
    return (String) getPropertyValue(true, emptyString, gdriveNewClientSecret);
  }

  public void setGdriveNewClientSecret(String gdriveNewClientSecret) {
    this.gdriveNewClientSecret = gdriveNewClientSecret;
  }

  public String getGdriveNewRedirectUri() {
    return (String) getPropertyValue(true, emptyString, gdriveNewRedirectUri);
  }

  public void setGdriveNewRedirectUri(String gdriveNewRedirectUri) {
    this.gdriveNewRedirectUri = gdriveNewRedirectUri;
  }

  public String getTrimbleClientId() {
    return (String) getPropertyValue(trimbleClientId);
  }

  public void setTrimbleClientId(String trimbleClientId) {
    this.trimbleClientId = trimbleClientId;
  }

  public String getTrimbleClientSecret() {
    return (String) getPropertyValue(trimbleClientSecret);
  }

  public void setTrimbleClientSecret(String trimbleClientSecret) {
    this.trimbleClientSecret = trimbleClientSecret;
  }

  public String getTrimbleTokenUrl() {
    return (String) getPropertyValue(trimbleTokenUrl);
  }

  public void setTrimbleTokenUrl(String trimbleTokenUrl) {
    this.trimbleTokenUrl = trimbleTokenUrl;
  }

  public String getTrimbleApiUrl() {
    return (String) getPropertyValue(trimbleApiUrl);
  }

  public void setTrimbleApiUrl(String trimbleApiUrl) {
    this.trimbleApiUrl = trimbleApiUrl;
  }

  public String getTrimbleOauthUrl() {
    return (String) getPropertyValue(trimbleOauthUrl);
  }

  public void setTrimbleOauthUrl(String trimbleOauthUrl) {
    this.trimbleOauthUrl = trimbleOauthUrl;
  }

  public String getTrimbleRedirectUri() {
    return (String) getPropertyValue(trimbleRedirectUri);
  }

  public void setTrimbleRedirectUri(String trimbleRedirectUri) {
    this.trimbleRedirectUri = trimbleRedirectUri;
  }

  public Boolean getOnedrive() {
    return (Boolean) getPropertyValue(onedrive);
  }

  public void setOnedrive(Boolean onedrive) {
    this.onedrive = onedrive;
  }

  public Boolean getOnshape() {
    return (Boolean) getPropertyValue(onshape);
  }

  public void setOnshape(Boolean onshape) {
    this.onshape = onshape;
  }

  public Boolean getHancom() {
    return (Boolean) getPropertyValue(hancom);
  }

  public void setHancom(Boolean hancom) {
    this.hancom = hancom;
  }

  public Boolean getOnshapedev() {
    return (Boolean) getPropertyValue(onshapedev);
  }

  public void setOnshapedev(Boolean onshapedev) {
    this.onshapedev = onshapedev;
  }

  public Boolean getOnshapestaging() {
    return (Boolean) getPropertyValue(onshapestaging);
  }

  public void setOnshapestaging(Boolean onshapestaging) {
    this.onshapestaging = onshapestaging;
  }

  public String getOnshapedevClientId() {
    return (String)
        getPropertyValue(true, "6GZBH2KI3UVSIN4AASHJDCCQODLZDPKTOO52KGA=", onshapedevClientId);
  }

  public void setOnshapedevClientId(String onshapedevClientId) {
    this.onshapedevClientId = onshapedevClientId;
  }

  public String getOnshapedevClientSecret() {
    return (String) getPropertyValue(
        true, "H3WFNF56ROM5637BSR7KSMIMYX375MUVXYRHAPOG5I6TE26EQEFA====", onshapedevClientSecret);
  }

  public void setOnshapedevClientSecret(String onshapedevClientSecret) {
    this.onshapedevClientSecret = onshapedevClientSecret;
  }

  public String getOnshapedevOauthUrl() {
    return (String) getPropertyValue(onshapedevOauthUrl);
  }

  public void setOnshapedevOauthUrl(String onshapedevOauthUrl) {
    this.onshapedevOauthUrl = onshapedevOauthUrl;
  }

  public String getOnshapedevApiUrl() {
    return (String) getPropertyValue(onshapedevApiUrl);
  }

  public void setOnshapedevApiUrl(String onshapedevApiUrl) {
    this.onshapedevApiUrl = onshapedevApiUrl;
  }

  public String getOnshapedevRedirectUri() {
    return (String) getPropertyValue(true, emptyString, onshapedevRedirectUri);
  }

  public void setOnshapedevRedirectUri(String onshapedevRedirectUri) {
    this.onshapedevRedirectUri = onshapedevRedirectUri;
  }

  public String getOnshapestagingClientId() {
    return (String)
        getPropertyValue(true, "6GZBH2KI3UVSIN4AASHJDCCQODLZDPKTOO52KGA=", onshapestagingClientId);
  }

  public void setOnshapestagingClientId(String onshapestagingClientId) {
    this.onshapestagingClientId = onshapestagingClientId;
  }

  public String getOnshapestagingClientSecret() {
    return (String) getPropertyValue(
        true,
        "H3WFNF56ROM5637BSR7KSMIMYX375MUVXYRHAPOG5I6TE26EQEFA====",
        onshapestagingClientSecret);
  }

  public void setOnshapestagingClientSecret(String onshapestagingClientSecret) {
    this.onshapestagingClientSecret = onshapestagingClientSecret;
  }

  public String getOnshapestagingOauthUrl() {
    return (String) getPropertyValue(onshapestagingOauthUrl);
  }

  public void setOnshapestagingOauthUrl(String onshapestagingOauthUrl) {
    this.onshapestagingOauthUrl = onshapestagingOauthUrl;
  }

  public String getOnshapestagingApiUrl() {
    return (String) getPropertyValue(onshapestagingApiUrl);
  }

  public void setOnshapestagingApiUrl(String onshapestagingApiUrl) {
    this.onshapestagingApiUrl = onshapestagingApiUrl;
  }

  public String getOnshapestagingRedirectUri() {
    return (String) getPropertyValue(true, emptyString, onshapestagingRedirectUri);
  }

  public void setOnshapestagingRedirectUri(String onshapestagingRedirectUri) {
    this.onshapestagingRedirectUri = onshapestagingRedirectUri;
  }

  public String getOnshapeClientId() {
    return (String)
        getPropertyValue(true, "6GZBH2KI3UVSIN4AASHJDCCQODLZDPKTOO52KGA=", onshapeClientId);
  }

  public void setOnshapeClientId(String onshapeClientId) {
    this.onshapeClientId = onshapeClientId;
  }

  public String getOnshapeClientSecret() {
    return (String) getPropertyValue(
        true, "H3WFNF56ROM5637BSR7KSMIMYX375MUVXYRHAPOG5I6TE26EQEFA====", onshapeClientSecret);
  }

  public void setOnshapeClientSecret(String onshapeClientSecret) {
    this.onshapeClientSecret = onshapeClientSecret;
  }

  public String getOnshapeOauthUrl() {
    return (String) getPropertyValue(onshapeOauthUrl);
  }

  public void setOnshapeOauthUrl(String onshapeOauthUrl) {
    this.onshapeOauthUrl = onshapeOauthUrl;
  }

  public String getOnshapeApiUrl() {
    return (String) getPropertyValue(onshapeApiUrl);
  }

  public void setOnshapeApiUrl(String onshapeApiUrl) {
    this.onshapeApiUrl = onshapeApiUrl;
  }

  public String getOnshapeRedirectUri() {
    return (String) getPropertyValue(true, emptyString, onshapeRedirectUri);
  }

  public void setOnshapeRedirectUri(String onshapeRedirectUri) {
    this.onshapeRedirectUri = onshapeRedirectUri;
  }

  public String getHancomProxyUrl() {
    return (String) getPropertyValue(true, emptyString, hancomProxyUrl);
  }

  public void setHancomProxyUrl(String hancomProxyUrl) {
    this.hancomProxyUrl = hancomProxyUrl;
  }

  public String getHancomProxyPort() {
    return (String) getPropertyValue(true, "0", hancomProxyPort);
  }

  public void setHancomProxyPort(String hancomProxyPort) {
    this.hancomProxyPort = hancomProxyPort;
  }

  public String getHancomProxyLogin() {
    return (String) getPropertyValue(true, emptyString, hancomProxyLogin);
  }

  public void setHancomProxyLogin(String hancomProxyLogin) {
    this.hancomProxyLogin = hancomProxyLogin;
  }

  public String getHancomProxyPass() {
    return (String) getPropertyValue(true, emptyString, hancomProxyPass);
  }

  public void setHancomProxyPass(String hancomProxyPass) {
    this.hancomProxyPass = hancomProxyPass;
  }

  public String getHancomstgProxyUrl() {
    return (String) getPropertyValue(true, emptyString, hancomstgProxyUrl);
  }

  public void setHancomstgProxyUrl(String hancomstgProxyUrl) {
    this.hancomstgProxyUrl = hancomstgProxyUrl;
  }

  public String getHancomstgProxyPort() {
    return (String) getPropertyValue(true, "0", hancomstgProxyPort);
  }

  public void setHancomstgProxyPort(String hancomstgProxyPort) {
    this.hancomstgProxyPort = hancomstgProxyPort;
  }

  public String getHancomstgProxyLogin() {
    return (String) getPropertyValue(true, emptyString, hancomstgProxyLogin);
  }

  public void setHancomstgProxyLogin(String hancomstgProxyLogin) {
    this.hancomstgProxyLogin = hancomstgProxyLogin;
  }

  public String getHancomstgProxyPass() {
    return (String) getPropertyValue(true, emptyString, hancomstgProxyPass);
  }

  public void setHancomstgProxyPass(String hancomstgProxyPass) {
    this.hancomstgProxyPass = hancomstgProxyPass;
  }

  public String getSesRegion() {
    return (String) getPropertyValue(sesRegion);
  }

  public void setSesRegion(String sesRegion) {
    this.sesRegion = sesRegion;
  }

  public String getSmtpSender() {
    return (String) getPropertyValue(true, "alerts@graebert.com", smtpSender);
  }

  public void setSmtpSender(String smtpSender) {
    this.smtpSender = smtpSender;
  }

  public String getSmtpProName() {
    return (String) getPropertyValue(true, "ARES Kudo", smtpProName);
  }

  public void setSmtpProName(String smtpProName) {
    this.smtpProName = smtpProName;
  }

  public Boolean getSmtpEnableDemo() {
    return (Boolean) getPropertyValue(true, false, smtpEnableDemo);
  }

  public void setSmtpEnableDemo(Boolean smtpEnableDemo) {
    this.smtpEnableDemo = smtpEnableDemo;
  }

  public String getPostmarkClientId() {
    return (String) getPropertyValue(postmarkClientId);
  }

  public void setPostmarkClientId(String postmarkClientId) {
    this.postmarkClientId = postmarkClientId;
  }

  public Boolean getDevMode() {
    return (Boolean) getPropertyValue(devMode);
  }

  public void setDevMode(Boolean devMode) {
    this.devMode = devMode;
  }

  public String getElasticacheEndpoint() {
    return (String) getPropertyValue(elasticacheEndpoint);
  }

  public void setElasticacheEndpoint(String elasticacheEndpoint) {
    this.elasticacheEndpoint = elasticacheEndpoint;
  }

  public int getElasticachePort() {
    return (int) getPropertyValue(elasticachePort);
  }

  public void setElasticachePort(int elasticachePort) {
    this.elasticachePort = elasticachePort;
  }

  public Boolean getElasticacheEnabled() {
    return (Boolean) getPropertyValue(elasticacheEnabled);
  }

  public void setElasticacheEnabled(Boolean elasticacheEnabled) {
    this.elasticacheEnabled = elasticacheEnabled;
  }

  public String getDefaultUserOptions() {
    return (String) getPropertyValue(true, emptyString, defaultUserOptions);
  }

  public JsonObject getDefaultUserOptionsAsJson() {
    String jsonAsString = getDefaultUserOptions();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setDefaultUserOptions(String defaultUserOptions) {
    this.defaultUserOptions = defaultUserOptions;
  }

  public String getInstanceOptions() {
    return (String) getPropertyValue(true, emptyString, instanceOptions);
  }

  public JsonObject getInstanceOptionsAsJson() {
    String jsonAsString = getInstanceOptions();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setInstanceOptions(String instanceOptions) {
    this.instanceOptions = instanceOptions;
  }

  public String getInstanceStorages() {
    return (String) getPropertyValue(true, emptyString, instanceStorages);
  }

  public JsonObject getInstanceStoragesAsJson() {
    String jsonAsString = getInstanceStorages();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setInstanceStorages(String instanceStorages) {
    this.instanceStorages = instanceStorages;
  }

  public String getDefaultCompanyOptions() {
    return (String) getPropertyValue(true, emptyString, defaultCompanyOptions);
  }

  public JsonObject getDefaultCompanyOptionsAsJson() {
    String jsonAsString = getDefaultCompanyOptions();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setDefaultCompanyOptions(String defaultCompanyOptions) {
    this.defaultCompanyOptions = defaultCompanyOptions;
  }

  public String getUserPreferences() {
    return (String) getPropertyValue(true, emptyString, userPreferences);
  }

  public JsonObject getUserPreferencesAsJson() {
    String jsonAsString = getUserPreferences();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setUserPreferences(String userPreferences) {
    this.userPreferences = userPreferences;
  }

  public String getLicensingUrl() {
    return (String) getPropertyValue(licensingUrl);
  }

  public void setLicensingUrl(String licensingUrl) {
    this.licensingUrl = licensingUrl;
  }

  public String getWebsocketUrl() {
    return (String) getPropertyValue(websocketUrl);
  }

  public void setWebsocketUrl(String websocketUrl) {
    this.websocketUrl = websocketUrl;
  }

  public String getWebsocketApikey() {
    return (String) getPropertyValue(websocketApikey);
  }

  public void setWebsocketApikey(String websocketApikey) {
    this.websocketApikey = websocketApikey;
  }

  public Boolean getWebsocketEnabled() {
    return (Boolean) getPropertyValue(websocketEnabled);
  }

  public void setWebsocketEnabled(Boolean websocketEnabled) {
    this.websocketEnabled = websocketEnabled;
  }

  public Boolean getNotificationsEnabled() {
    return (Boolean) getPropertyValue(notificationsEnabled);
  }

  public void setNotificationsEnabled(Boolean notificationsEnabled) {
    this.notificationsEnabled = notificationsEnabled;
  }

  public String getOauthUrl() {
    return (String) getPropertyValue(oauthUrl);
  }

  public void setOauthUrl(String oauthUrl) {
    this.oauthUrl = oauthUrl;
  }

  public Boolean getxRayEnabled() {
    return (Boolean) getPropertyValue(true, true, xRayEnabled);
  }

  public void setxRayEnabled(Boolean xRayEnabled) {
    this.xRayEnabled = xRayEnabled;
  }

  public Boolean getEnterprise() {
    return (Boolean) getPropertyValue(enterprise);
  }

  public void setEnterprise(Boolean enterprise) {
    this.enterprise = enterprise;
  }

  public String getRevision() {
    return (String) getPropertyValue(revision);
  }

  public void setRevision(String revision) {
    this.revision = revision;
  }

  public String getDefaultLocale() {
    return (String) getPropertyValue(defaultLocale);
  }

  public void setDefaultLocale(String defaultLocale) {
    this.defaultLocale = defaultLocale;
  }

  public String getSamplesName() {
    return (String) getPropertyValue(samplesName);
  }

  public void setSamplesName(String samplesName) {
    this.samplesName = samplesName;
  }

  public String getSamplesIcon() {
    return (String) getPropertyValue(samplesIcon);
  }

  public void setSamplesIcon(String samplesIcon) {
    this.samplesIcon = samplesIcon;
  }

  public String getSamplesIconBlack() {
    return (String) getPropertyValue(samplesIconBlack);
  }

  public void setSamplesIconBlack(String samplesIconBlack) {
    this.samplesIconBlack = samplesIconBlack;
  }

  public String getSamplesIconPng() {
    return (String) getPropertyValue(samplesIconPng);
  }

  public void setSamplesIconPng(String samplesIconPng) {
    this.samplesIconPng = samplesIconPng;
  }

  public String getSamplesIconBlackPng() {
    return (String) getPropertyValue(samplesIconBlackPng);
  }

  public void setSamplesIconBlackPng(String samplesIconBlackPng) {
    this.samplesIconBlackPng = samplesIconBlackPng;
  }

  public String getSimpleStorage() {
    return (String) getPropertyValue(true, emptyString, simpleStorage);
  }

  public JsonObject getSimpleStorageAsJson() {
    String jsonAsString = getSimpleStorage();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setSimpleStorage(String simpleStorage) {
    this.simpleStorage = simpleStorage;
  }

  public String getKinesisRegion() {
    return (String) getPropertyValue(kinesisRegion);
  }

  public void setKinesisRegion(String kinesisRegion) {
    this.kinesisRegion = kinesisRegion;
  }

  public String getKinesisSessionLogStream() {
    return (String) getPropertyValue(kinesisSessionLogStream);
  }

  public void setKinesisSessionLogStream(String kinesisSessionLogStream) {
    this.kinesisSessionLogStream = kinesisSessionLogStream;
  }

  public String getKinesisEmailLogStream() {
    return (String) getPropertyValue(kinesisEmailLogStream);
  }

  public void setKinesisEmailLogStream(String kinesisEmailLogStream) {
    this.kinesisEmailLogStream = kinesisEmailLogStream;
  }

  public String getKinesisSharingLogStream() {
    return (String) getPropertyValue(kinesisSharingLogStream);
  }

  public void setKinesisSharingLogStream(String kinesisSharingLogStream) {
    this.kinesisSharingLogStream = kinesisSharingLogStream;
  }

  public String getKinesisSubscriptionLogStream() {
    return (String) getPropertyValue(kinesisSubscriptionLogStream);
  }

  public void setKinesisSubscriptionLogStream(String kinesisSubscriptionLogStream) {
    this.kinesisSubscriptionLogStream = kinesisSubscriptionLogStream;
  }

  public String getKinesisFilesLogStream() {
    return (String) getPropertyValue(kinesisFilesLogStream);
  }

  public void setKinesisFilesLogStream(String kinesisFilesLogStream) {
    this.kinesisFilesLogStream = kinesisFilesLogStream;
  }

  public String getKinesisStorageLogStream() {
    return (String) getPropertyValue(kinesisStorageLogStream);
  }

  public void setKinesisStorageLogStream(String kinesisStorageLogStream) {
    this.kinesisStorageLogStream = kinesisStorageLogStream;
  }

  public String getKinesisBlockLibraryLogStream() {
    return (String) getPropertyValue(kinesisBlockLibraryLogStream);
  }

  public void setKinesisBlockLibraryLogStream(String kinesisBlockLibraryLogStream) {
    this.kinesisBlockLibraryLogStream = kinesisBlockLibraryLogStream;
  }

  public String getKinesisMentionLogStream() {
    return (String) getPropertyValue(kinesisMentionLogStream);
  }

  public void setKinesisMentionLogStream(String kinesisMentionLogStream) {
    this.kinesisMentionLogStream = kinesisMentionLogStream;
  }

  public String getKinesisDataplaneLogStream() {
    return kinesisDataplaneLogStream;
  }

  public void setKinesisDataplaneLogStream(String kinesisDataplaneLogStream) {
    this.kinesisDataplaneLogStream = kinesisDataplaneLogStream;
  }

  public int getKinesisBatchLimit() {
    return (int) getPropertyValue(kinesisBatchLimit);
  }

  public void setKinesisBatchLimit(int kinesisBatchLimit) {
    this.kinesisBatchLimit = kinesisBatchLimit;
  }

  public String getKinesisFilePath() {
    return (String) getPropertyValue(kinesisFilePath);
  }

  public void setKinesisFilePath(String kinesisFilePath) {
    this.kinesisFilePath = kinesisFilePath;
  }

  public String getFontsBucket() {
    return (String) getPropertyValue(fontsBucket);
  }

  public void setFontsBucket(String fontsBucket) {
    this.fontsBucket = fontsBucket;
  }

  public String getFontsRegion() {
    return (String) getPropertyValue(fontsRegion);
  }

  public void setFontsRegion(String fontsRegion) {
    this.fontsRegion = fontsRegion;
  }

  public String getBoxName() {
    return (String) getPropertyValue(boxName);
  }

  public void setBoxName(String boxName) {
    this.boxName = boxName;
  }

  public String getBoxIcon() {
    return (String) getPropertyValue(boxIcon);
  }

  public void setBoxIcon(String boxIcon) {
    this.boxIcon = boxIcon;
  }

  public String getBoxIconBlack() {
    return (String) getPropertyValue(boxIconBlack);
  }

  public void setBoxIconBlack(String boxIconBlack) {
    this.boxIconBlack = boxIconBlack;
  }

  public String getBoxIconPng() {
    return (String) getPropertyValue(boxIconPng);
  }

  public void setBoxIconPng(String boxIconPng) {
    this.boxIconPng = boxIconPng;
  }

  public String getBoxIconBlackPng() {
    return (String) getPropertyValue(boxIconBlackPng);
  }

  public void setBoxIconBlackPng(String boxIconBlackPng) {
    this.boxIconBlackPng = boxIconBlackPng;
  }

  public String getGdriveName() {
    return (String) getPropertyValue(gdriveName);
  }

  public void setGdriveName(String gdriveName) {
    this.gdriveName = gdriveName;
  }

  public String getGdriveIcon() {
    return (String) getPropertyValue(gdriveIcon);
  }

  public void setGdriveIcon(String gdriveIcon) {
    this.gdriveIcon = gdriveIcon;
  }

  public String getGdriveIconBlack() {
    return (String) getPropertyValue(gdriveIconBlack);
  }

  public void setGdriveIconBlack(String gdriveIconBlack) {
    this.gdriveIconBlack = gdriveIconBlack;
  }

  public String getGdriveIconPng() {
    return (String) getPropertyValue(gdriveIconPng);
  }

  public void setGdriveIconPng(String gdriveIconPng) {
    this.gdriveIconPng = gdriveIconPng;
  }

  public String getGdriveIconBlackPng() {
    return (String) getPropertyValue(gdriveIconBlackPng);
  }

  public void setGdriveIconBlackPng(String gdriveIconBlackPng) {
    this.gdriveIconBlackPng = gdriveIconBlackPng;
  }

  public String getDropboxName() {
    return (String) getPropertyValue(dropboxName);
  }

  public void setDropboxName(String dropboxName) {
    this.dropboxName = dropboxName;
  }

  public String getDropboxIcon() {
    return (String) getPropertyValue(dropboxIcon);
  }

  public void setDropboxIcon(String dropboxIcon) {
    this.dropboxIcon = dropboxIcon;
  }

  public String getDropboxIconBlack() {
    return (String) getPropertyValue(dropboxIconBlack);
  }

  public void setDropboxIconBlack(String dropboxIconBlack) {
    this.dropboxIconBlack = dropboxIconBlack;
  }

  public String getDropboxIconPng() {
    return (String) getPropertyValue(dropboxIconPng);
  }

  public void setDropboxIconPng(String dropboxIconPng) {
    this.dropboxIconPng = dropboxIconPng;
  }

  public String getDropboxIconBlackPng() {
    return (String) getPropertyValue(dropboxIconBlackPng);
  }

  public void setDropboxIconBlackPng(String dropboxIconBlackPng) {
    this.dropboxIconBlackPng = dropboxIconBlackPng;
  }

  public String getOnedriveName() {
    return (String) getPropertyValue(onedriveName);
  }

  public void setOnedriveName(String onedriveName) {
    this.onedriveName = onedriveName;
  }

  public String getOnedriveIcon() {
    return (String) getPropertyValue(onedriveIcon);
  }

  public void setOnedriveIcon(String onedriveIcon) {
    this.onedriveIcon = onedriveIcon;
  }

  public String getOnedriveIconBlack() {
    return (String) getPropertyValue(onedriveIconBlack);
  }

  public void setOnedriveIconBlack(String onedriveIconBlack) {
    this.onedriveIconBlack = onedriveIconBlack;
  }

  public String getOnedriveIconPng() {
    return (String) getPropertyValue(onedriveIconPng);
  }

  public void setOnedriveIconPng(String onedriveIconPng) {
    this.onedriveIconPng = onedriveIconPng;
  }

  public String getOnedriveIconBlackPng() {
    return (String) getPropertyValue(onedriveIconBlackPng);
  }

  public void setOnedriveIconBlackPng(String onedriveIconBlackPng) {
    this.onedriveIconBlackPng = onedriveIconBlackPng;
  }

  public String getSharepointName() {
    return (String) getPropertyValue(sharepointName);
  }

  public void setSharepointName(String sharepointName) {
    this.sharepointName = sharepointName;
  }

  public String getSharepointIcon() {
    return (String) getPropertyValue(sharepointIcon);
  }

  public void setSharepointIcon(String sharepointIcon) {
    this.sharepointIcon = sharepointIcon;
  }

  public String getSharepointIconBlack() {
    return (String) getPropertyValue(sharepointIconBlack);
  }

  public void setSharepointIconBlack(String sharepointIconBlack) {
    this.sharepointIconBlack = sharepointIconBlack;
  }

  public String getSharepointIconPng() {
    return (String) getPropertyValue(sharepointIconPng);
  }

  public void setSharepointIconPng(String sharepointIconPng) {
    this.sharepointIconPng = sharepointIconPng;
  }

  public String getSharepointIconBlackPng() {
    return (String) getPropertyValue(sharepointIconBlackPng);
  }

  public void setSharepointIconBlackPng(String sharepointIconBlackPng) {
    this.sharepointIconBlackPng = sharepointIconBlackPng;
  }

  public String getTrimbleName() {
    return (String) getPropertyValue(trimbleName);
  }

  public void setTrimbleName(String trimbleName) {
    this.trimbleName = trimbleName;
  }

  public String getTrimbleIcon() {
    return (String) getPropertyValue(trimbleIcon);
  }

  public void setTrimbleIcon(String trimbleIcon) {
    this.trimbleIcon = trimbleIcon;
  }

  public String getTrimbleIconBlack() {
    return (String) getPropertyValue(trimbleIconBlack);
  }

  public void setTrimbleIconBlack(String trimbleIconBlack) {
    this.trimbleIconBlack = trimbleIconBlack;
  }

  public String getTrimbleIconPng() {
    return (String) getPropertyValue(trimbleIconPng);
  }

  public void setTrimbleIconPng(String trimbleIconPng) {
    this.trimbleIconPng = trimbleIconPng;
  }

  public String getTrimbleIconBlackPng() {
    return (String) getPropertyValue(trimbleIconBlackPng);
  }

  public void setTrimbleIconBlackPng(String trimbleIconBlackPng) {
    this.trimbleIconBlackPng = trimbleIconBlackPng;
  }

  public String getWebdavName() {
    return (String) getPropertyValue(webdavName);
  }

  public void setWebdavName(String webdavName) {
    this.webdavName = webdavName;
  }

  public String getWebdavIcon() {
    return (String) getPropertyValue(webdavIcon);
  }

  public void setWebdavIcon(String webdavIcon) {
    this.webdavIcon = webdavIcon;
  }

  public String getWebdavIconBlack() {
    return (String) getPropertyValue(webdavIconBlack);
  }

  public void setWebdavIconBlack(String webdavIconBlack) {
    this.webdavIconBlack = webdavIconBlack;
  }

  public String getWebdavIconPng() {
    return (String) getPropertyValue(webdavIconPng);
  }

  public void setWebdavIconPng(String webdavIconPng) {
    this.webdavIconPng = webdavIconPng;
  }

  public String getWebdavIconBlackPng() {
    return (String) getPropertyValue(webdavIconBlackPng);
  }

  public void setWebdavIconBlackPng(String webdavIconBlackPng) {
    this.webdavIconBlackPng = webdavIconBlackPng;
  }

  public String getNextcloudName() {
    return (String) getPropertyValue(nextcloudName);
  }

  public void setNextcloudName(String nextcloudName) {
    this.nextcloudName = nextcloudName;
  }

  public String getNextcloudIcon() {
    return (String) getPropertyValue(nextcloudIcon);
  }

  public void setNextcloudIcon(String nextcloudIcon) {
    this.nextcloudIcon = nextcloudIcon;
  }

  public String getNextcloudIconBlack() {
    return (String) getPropertyValue(nextcloudIconBlack);
  }

  public void setNextcloudIconBlack(String nextcloudIconBlack) {
    this.nextcloudIconBlack = nextcloudIconBlack;
  }

  public String getNextcloudIconPng() {
    return (String) getPropertyValue(nextcloudIconPng);
  }

  public void setNextcloudIconPng(String nextcloudIconPng) {
    this.nextcloudIconPng = nextcloudIconPng;
  }

  public String getNextcloudIconBlackPng() {
    return (String) getPropertyValue(nextcloudIconBlackPng);
  }

  public void setNextcloudIconBlackPng(String nextcloudIconBlackPng) {
    this.nextcloudIconBlackPng = nextcloudIconBlackPng;
  }

  public String getOnshapestagingName() {
    return (String) getPropertyValue(onshapestagingName);
  }

  public void setOnshapestagingName(String onshapestagingName) {
    this.onshapestagingName = onshapestagingName;
  }

  public String getOnshapestagingIcon() {
    return (String) getPropertyValue(onshapestagingIcon);
  }

  public void setOnshapestagingIcon(String onshapestagingIcon) {
    this.onshapestagingIcon = onshapestagingIcon;
  }

  public String getOnshapestagingIconBlack() {
    return (String) getPropertyValue(onshapestagingIconBlack);
  }

  public void setOnshapestagingIconBlack(String onshapestagingIconBlack) {
    this.onshapestagingIconBlack = onshapestagingIconBlack;
  }

  public String getOnshapestagingIconPng() {
    return (String) getPropertyValue(onshapestagingIconPng);
  }

  public void setOnshapestagingIconPng(String onshapestagingIconPng) {
    this.onshapestagingIconPng = onshapestagingIconPng;
  }

  public String getOnshapestagingIconBlackPng() {
    return (String) getPropertyValue(onshapestagingIconBlackPng);
  }

  public void setOnshapestagingIconBlackPng(String onshapestagingIconBlackPng) {
    this.onshapestagingIconBlackPng = onshapestagingIconBlackPng;
  }

  public String getOnshapedevName() {
    return (String) getPropertyValue(onshapedevName);
  }

  public void setOnshapedevName(String onshapedevName) {
    this.onshapedevName = onshapedevName;
  }

  public String getOnshapedevIcon() {
    return (String) getPropertyValue(onshapedevIcon);
  }

  public void setOnshapedevIcon(String onshapedevIcon) {
    this.onshapedevIcon = onshapedevIcon;
  }

  public String getOnshapedevIconBlack() {
    return (String) getPropertyValue(onshapedevIconBlack);
  }

  public void setOnshapedevIconBlack(String onshapedevIconBlack) {
    this.onshapedevIconBlack = onshapedevIconBlack;
  }

  public String getOnshapedevIconPng() {
    return (String) getPropertyValue(onshapedevIconPng);
  }

  public void setOnshapedevIconPng(String onshapedevIconPng) {
    this.onshapedevIconPng = onshapedevIconPng;
  }

  public String getOnshapedevIconBlackPng() {
    return (String) getPropertyValue(onshapedevIconBlackPng);
  }

  public void setOnshapedevIconBlackPng(String onshapedevIconBlackPng) {
    this.onshapedevIconBlackPng = onshapedevIconBlackPng;
  }

  public String getOnshapeName() {
    return (String) getPropertyValue(onshapeName);
  }

  public void setOnshapeName(String onshapeName) {
    this.onshapeName = onshapeName;
  }

  public String getOnshapeIcon() {
    return (String) getPropertyValue(onshapeIcon);
  }

  public void setOnshapeIcon(String onshapeIcon) {
    this.onshapeIcon = onshapeIcon;
  }

  public String getOnshapeIconBlack() {
    return (String) getPropertyValue(onshapeIconBlack);
  }

  public void setOnshapeIconBlack(String onshapeIconBlack) {
    this.onshapeIconBlack = onshapeIconBlack;
  }

  public String getOnshapeIconPng() {
    return (String) getPropertyValue(onshapeIconPng);
  }

  public void setOnshapeIconPng(String onshapeIconPng) {
    this.onshapeIconPng = onshapeIconPng;
  }

  public String getOnshapeIconBlackPng() {
    return (String) getPropertyValue(onshapeIconBlackPng);
  }

  public void setOnshapeIconBlackPng(String onshapeIconBlackPng) {
    this.onshapeIconBlackPng = onshapeIconBlackPng;
  }

  public String getHancomName() {
    return (String) getPropertyValue(hancomName);
  }

  public void setHancomName(String hancomName) {
    this.hancomName = hancomName;
  }

  public String getHancomIcon() {
    return (String) getPropertyValue(hancomIcon);
  }

  public void setHancomIcon(String hancomIcon) {
    this.hancomIcon = hancomIcon;
  }

  public String getHancomIconBlack() {
    return (String) getPropertyValue(hancomIconBlack);
  }

  public void setHancomIconBlack(String hancomIconBlack) {
    this.hancomIconBlack = hancomIconBlack;
  }

  public String getHancomIconPng() {
    return (String) getPropertyValue(hancomIconPng);
  }

  public void setHancomIconPng(String hancomIconPng) {
    this.hancomIconPng = hancomIconPng;
  }

  public String getHancomIconBlackPng() {
    return (String) getPropertyValue(hancomIconBlackPng);
  }

  public void setHancomIconBlackPng(String hancomIconBlackPng) {
    this.hancomIconBlackPng = hancomIconBlackPng;
  }

  public String getHancomstgName() {
    return (String) getPropertyValue(hancomstgName);
  }

  public void setHancomstgName(String hancomstgName) {
    this.hancomstgName = hancomstgName;
  }

  public String getHancomstgIcon() {
    return (String) getPropertyValue(hancomstgIcon);
  }

  public void setHancomstgIcon(String hancomstgIcon) {
    this.hancomstgIcon = hancomstgIcon;
  }

  public String getHancomstgIconBlack() {
    return (String) getPropertyValue(hancomstgIconBlack);
  }

  public void setHancomstgIconBlack(String hancomstgIconBlack) {
    this.hancomstgIconBlack = hancomstgIconBlack;
  }

  public String getHancomstgIconPng() {
    return (String) getPropertyValue(hancomstgIconPng);
  }

  public void setHancomstgIconPng(String hancomstgIconPng) {
    this.hancomstgIconPng = hancomstgIconPng;
  }

  public String getHancomstgIconBlackPng() {
    return (String) getPropertyValue(hancomstgIconBlackPng);
  }

  public void setHancomstgIconBlackPng(String hancomstgIconBlackPng) {
    this.hancomstgIconBlackPng = hancomstgIconBlackPng;
  }

  public Boolean getSamlCheckEntitlement() {
    return (Boolean) getPropertyValue(samlCheckEntitlement);
  }

  public void setSamlCheckEntitlement(Boolean samlCheckEntitlement) {
    this.samlCheckEntitlement = samlCheckEntitlement;
  }

  public String getSamlEntitlement() {
    return (String) getPropertyValue(samlEntitlement);
  }

  public void setSamlEntitlement(String samlEntitlement) {
    this.samlEntitlement = samlEntitlement;
  }

  public Boolean getSamlCheckDomain() {
    return (Boolean) getPropertyValue(samlCheckDomain);
  }

  public void setSamlCheckDomain(Boolean samlCheckDomain) {
    this.samlCheckDomain = samlCheckDomain;
  }

  public String getSamlWhitelist() {
    return (String) getPropertyValue(samlWhitelist);
  }

  public JsonArray getSamlWhitelistAsArray() {
    String arrayAsString = getSamlWhitelist();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setSamlWhitelist(String samlWhitelist) {
    this.samlWhitelist = samlWhitelist;
  }

  public int getMaxUploadSize() {
    return (int) getPropertyValue(maxUploadSize);
  }

  public void setMaxUploadSize(int maxUploadSize) {
    this.maxUploadSize = maxUploadSize;
  }

  public String getSecurityHeaderSchema() {
    return (String) getPropertyValue(securityHeaderSchema);
  }

  public void setSecurityHeaderSchema(String securityHeaderSchema) {
    this.securityHeaderSchema = securityHeaderSchema;
  }

  public String getSecurityCookieSchema() {
    return (String) getPropertyValue(securityCookieSchema);
  }

  public void setSecurityCookieSchema(String securityCookieSchema) {
    this.securityCookieSchema = securityCookieSchema;
  }

  public Boolean getNewSessionWorkflow() {
    return (Boolean) getPropertyValue(true, false, newSessionWorkflow);
  }

  public void setNewSessionWorkflow(Boolean newSessionWorkflow) {
    this.newSessionWorkflow = newSessionWorkflow;
  }

  public int getMaxUserSessions() {
    return (int) getPropertyValue(maxUserSessions);
  }

  public void setMaxUserSessions(int maxUserSessions) {
    this.maxUserSessions = maxUserSessions;
  }

  public String getXKudoSecret() {
    return (String) getPropertyValue(xKudoSecret);
  }

  public void setXKudoSecret(String xKudoSecret) {
    this.xKudoSecret = xKudoSecret;
  }

  public String getSamplesFiles() {
    return (String) getPropertyValue(true, emptyString, samplesFiles);
  }

  public JsonObject getSamplesFilesAsJson() {
    String jsonAsString = getSamplesFiles();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setSamplesFiles(String samplesFiles) {
    this.samplesFiles = samplesFiles;
  }

  public Boolean getCheckExportCompliance() {
    return (Boolean) getPropertyValue(checkExportCompliance);
  }

  public void setCheckExportCompliance(Boolean checkExportCompliance) {
    this.checkExportCompliance = checkExportCompliance;
  }

  public String getShortenServiceURL() {
    return (String) getPropertyValue(true, null, shortenServiceURL);
  }

  public void setShortenServiceURL(String shortenServiceURL) {
    this.shortenServiceURL = shortenServiceURL;
  }

  public Boolean getReturnDownloadUrl() {
    return (Boolean) getPropertyValue(true, false, returnDownloadUrl);
  }

  public void setReturnDownloadUrl(Boolean returnDownloadUrl) {
    this.returnDownloadUrl = returnDownloadUrl;
  }

  public int getOnedriveThumbnailChunkSize() {
    return (int) getPropertyValue(onedriveThumbnailChunkSize);
  }

  public void setOnedriveThumbnailChunkSize(int onedriveThumbnailChunkSize) {
    this.onedriveThumbnailChunkSize = onedriveThumbnailChunkSize;
  }

  public int getOnedriveBusinessThumbnailChunkSize() {
    return (int) getPropertyValue(onedriveBusinessThumbnailChunkSize);
  }

  public void setOnedriveBusinessThumbnailChunkSize(int onedriveBusinessThumbnailChunkSize) {
    this.onedriveBusinessThumbnailChunkSize = onedriveBusinessThumbnailChunkSize;
  }

  public int getSharepointThumbnailChunkSize() {
    return (int) getPropertyValue(sharepointThumbnailChunkSize);
  }

  public void setSharepointThumbnailChunkSize(int sharepointThumbnailChunkSize) {
    this.sharepointThumbnailChunkSize = sharepointThumbnailChunkSize;
  }

  public int getBoxThumbnailChunkSize() {
    return (int) getPropertyValue(boxThumbnailChunkSize);
  }

  public void setBoxThumbnailChunkSize(int boxThumbnailChunkSize) {
    this.boxThumbnailChunkSize = boxThumbnailChunkSize;
  }

  public int getGdriveThumbnailChunkSize() {
    return (int) getPropertyValue(gdriveThumbnailChunkSize);
  }

  public void setGdriveThumbnailChunkSize(int gdriveThumbnailChunkSize) {
    this.gdriveThumbnailChunkSize = gdriveThumbnailChunkSize;
  }

  public int getDropboxThumbnailChunkSize() {
    return (int) getPropertyValue(dropboxThumbnailChunkSize);
  }

  public void setDropboxThumbnailChunkSize(int dropboxThumbnailChunkSize) {
    this.dropboxThumbnailChunkSize = dropboxThumbnailChunkSize;
  }

  public int getOnshapeThumbnailChunkSize() {
    return (int) getPropertyValue(onshapeThumbnailChunkSize);
  }

  public void setOnshapeThumbnailChunkSize(int onshapeThumbnailChunkSize) {
    this.onshapeThumbnailChunkSize = onshapeThumbnailChunkSize;
  }

  public int getOnshapedevThumbnailChunkSize() {
    return (int) getPropertyValue(onshapedevThumbnailChunkSize);
  }

  public void setOnshapedevThumbnailChunkSize(int onshapedevThumbnailChunkSize) {
    this.onshapedevThumbnailChunkSize = onshapedevThumbnailChunkSize;
  }

  public int getOnshapestagingThumbnailChunkSize() {
    return (int) getPropertyValue(onshapestagingThumbnailChunkSize);
  }

  public void setOnshapestagingThumbnailChunkSize(int onshapestagingThumbnailChunkSize) {
    this.onshapestagingThumbnailChunkSize = onshapestagingThumbnailChunkSize;
  }

  public int getSamplesThumbnailChunkSize() {
    return (int) getPropertyValue(samplesThumbnailChunkSize);
  }

  public void setSamplesThumbnailChunkSize(int samplesThumbnailChunkSize) {
    this.samplesThumbnailChunkSize = samplesThumbnailChunkSize;
  }

  public int getHancomThumbnailChunkSize() {
    return (int) getPropertyValue(hancomThumbnailChunkSize);
  }

  public void setHancomThumbnailChunkSize(int hancomThumbnailChunkSize) {
    this.hancomThumbnailChunkSize = hancomThumbnailChunkSize;
  }

  public int getHancomstgThumbnailChunkSize() {
    return (int) getPropertyValue(hancomstgThumbnailChunkSize);
  }

  public void setHancomstgThumbnailChunkSize(int hancomstgThumbnailChunkSize) {
    this.hancomstgThumbnailChunkSize = hancomstgThumbnailChunkSize;
  }

  public int getWebdavThumbnailChunkSize() {
    return (int) getPropertyValue(webdavThumbnailChunkSize);
  }

  public void setWebdavThumbnailChunkSize(int webdavThumbnailChunkSize) {
    this.webdavThumbnailChunkSize = webdavThumbnailChunkSize;
  }

  public int getNextcloudThumbnailChunkSize() {
    return (int) getPropertyValue(nextcloudThumbnailChunkSize);
  }

  public void setNextcloudThumbnailChunkSize(int nextcloudThumbnailChunkSize) {
    this.nextcloudThumbnailChunkSize = nextcloudThumbnailChunkSize;
  }

  public int getTrimbleThumbnailChunkSize() {
    return (int) getPropertyValue(trimbleThumbnailChunkSize);
  }

  public void setTrimbleThumbnailChunkSize(int trimbleThumbnailChunkSize) {
    this.trimbleThumbnailChunkSize = trimbleThumbnailChunkSize;
  }

  public String getBlocksSupportedFileTypes() {
    return (String) getPropertyValue(blocksSupportedFileTypes);
  }

  public JsonArray getBlocksSupportedFileTypesAsArray() {
    String arrayAsString = getBlocksSupportedFileTypes();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setBlocksSupportedFileTypes(String blocksSupportedFileTypes) {
    this.blocksSupportedFileTypes = blocksSupportedFileTypes;
  }

  public String getTemplatesSupportedFileTypes() {
    return (String) getPropertyValue(templatesSupportedFileTypes);
  }

  public JsonArray getTemplatesSupportedFileTypesAsArray() {
    String arrayAsString = getTemplatesSupportedFileTypes();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setTemplatesSupportedFileTypes(String templatesSupportedFileTypes) {
    this.templatesSupportedFileTypes = templatesSupportedFileTypes;
  }

  public String getFontsSupportedFileTypes() {
    return (String) getPropertyValue(fontsSupportedFileTypes);
  }

  public JsonArray getFontsSupportedFileTypesAsArray() {
    String arrayAsString = getFontsSupportedFileTypes();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setFontsSupportedFileTypes(String fontsSupportedFileTypes) {
    this.fontsSupportedFileTypes = fontsSupportedFileTypes;
  }

  public String getLispSupportedFileTypes() {
    return (String) getPropertyValue(lispSupportedFileTypes);
  }

  public JsonArray getLispSupportedFileTypesAsArray() {
    String arrayAsString = getLispSupportedFileTypes();
    return Objects.nonNull(arrayAsString) ? new JsonArray(arrayAsString) : new JsonArray();
  }

  public void setLispSupportedFileTypes(String lispSupportedFileTypes) {
    this.lispSupportedFileTypes = lispSupportedFileTypes;
  }

  public String getUrl() {
    return (String) getPropertyValue(url);
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public Boolean getScanUsersList() {
    return (Boolean) getPropertyValue(scanUsersList);
  }

  public void setScanUsersList(Boolean scanUsersList) {
    this.scanUsersList = scanUsersList;
  }

  private Object getPropertyValue(Object originalValue) {
    return getPropertyValue(false, null, originalValue);
  }

  private Object getPropertyValue(boolean optional, Object defaultValue, Object originalValue) {
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    if (stackTrace.length < 3) {
      throw new IllegalArgumentException("Could not get property in config");
    }
    String methodName = stackTrace[2].getMethodName();
    if (methodName.equals("getPropertyValue")) {
      methodName = stackTrace[3].getMethodName();
    }
    String fieldName = null;
    if (Utils.isStringNotNullOrEmpty(methodName)) {
      fieldName = methodName.substring("get".length());
    }
    try {
      if (!Utils.isStringNotNullOrEmpty(fieldName)) {
        throw new NoSuchFieldException();
      }
      String deCapitalizeField =
          Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
      this.getClass().getDeclaredField(deCapitalizeField);
    } catch (NoSuchFieldException e) {
      throw new IllegalArgumentException("Property \"" + fieldName + "\" not found in config");
    }
    if (optional) {
      if (Objects.nonNull(originalValue)) {
        return originalValue;
      } else {
        if (Objects.isNull(defaultValue)) {
          throw new IllegalArgumentException(
              "No default value assigned for the property \"" + fieldName + "\"");
        }
        return defaultValue;
      }
    } else {
      if (Objects.nonNull(originalValue)) {
        return originalValue;
      } else {
        throw new IllegalArgumentException(fieldName + " must be specified in config");
      }
    }
  }

  public static Properties fromJson(JsonObject config) throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return mapper.readValue(config.encode(), Properties.class);
  }
}
