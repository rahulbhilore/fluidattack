package com.graebert.storage.gridfs;

import com.amazonaws.HttpMethod;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ListVersionsRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.ResponseHeaderOverrides;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.VersionListing;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.util.Utils;
import io.vertx.core.buffer.Buffer;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import kong.unirest.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;

public class S3Regional {
  public static final Logger log = LogManager.getRootLogger();
  public static String pathSeparator = "/";
  private final String bucketName;
  private final String region;
  protected AmazonS3Client s3;
  protected ServerConfig config = null;

  public S3Regional(ServerConfig serverConfig, String bucket, String region) {
    if (Utils.isStringNotNullOrEmpty(bucket)
        && Utils.isStringNotNullOrEmpty(region)
        && isRegionValidAndSupported(region)) {
      this.region = region;
      bucketName = bucket;
    } else {
      if (serverConfig == null) {
        throw new IllegalArgumentException("ServerConfig isn't properly configured for S3");
      }
      config = serverConfig;
      // get default values from config
      this.region = config.getProperties().getS3Region();
      bucketName = config.getProperties().getS3Bucket();
    }
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
    builder.setRegion(this.region);
    s3 = (AmazonS3Client) builder.build();
  }

  public S3Regional(String bucket, String region) {
    if (Utils.isStringNotNullOrEmpty(bucket)
        && Utils.isStringNotNullOrEmpty(region)
        && isRegionValidAndSupported(region)) {
      this.region = region;
      bucketName = bucket;
    } else {
      throw new IllegalArgumentException(
          "Bucket and Region should be passed for proper initialization");
    }
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
    builder.setRegion(this.region);
    s3 = (AmazonS3Client) builder.build();
  }

  public S3Regional(ServerConfig serverConfig) {
    if (serverConfig == null) {
      throw new IllegalArgumentException("ServerConfig isn't properly configured for S3");
    }
    config = serverConfig;
    // get default values from config
    this.region = config.getProperties().getS3Region();
    bucketName = config.getProperties().getS3Bucket();
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
    builder.setRegion(this.region);
    s3 = (AmazonS3Client) builder.build();
  }

  public static boolean isRegionValidAndSupported(String id) {
    try {
      if (!Utils.isStringNotNullOrEmpty(id)) {
        return false;
      }
      Regions region = Regions.fromName(id);
      boolean isSupported = Region.getRegion(region).isServiceSupported(AmazonS3.ENDPOINT_PREFIX);
      if (!isSupported) {
        log.error("[S3Regional] region \"" + region + "\" is not supported");
      }
      return isSupported;
    } catch (Exception e) {
      log.error("[S3Regional] region \"" + id + "\" is not valid");
      return false;
    }
  }

  /**
   * Create key for presigned url request
   *
   * @param userId      - id of the current user
   * @param storageType - Key of the file to be uploaded
   * @return URL
   */
  public static String createPresignedKey(
      String presignedUploadId, String userId, String storageType) {
    List<String> keyBuilder = new ArrayList<>();
    keyBuilder.add(userId);
    String shortStorageType = StorageType.getShort(storageType);
    keyBuilder.add(shortStorageType);
    keyBuilder.add(presignedUploadId);
    return "presignedUploadedFiles/" + String.join("_", keyBuilder);
  }

  public String delete(String id) {
    return deleteFromBucket(bucketName, id);
  }

  public BucketLifecycleConfiguration getLifecycleConfiguration() {
    return s3.getBucketLifecycleConfiguration(bucketName);
  }

  public void deleteListOfKeysFromBucket(List<String> idsToDelete) {
    String[] ids = new String[idsToDelete.size()];
    idsToDelete.toArray(ids);
    deleteMultiObjectsFromBucket(ids);
  }

  public void deleteMultiObjectsFromBucket(String[] ids) {
    if (ids.length > 0) {
      DeleteObjectsRequest dor = new DeleteObjectsRequest(bucketName);
      dor.withKeys(ids);
      s3.deleteObjects(dor);
    }
  }

  public String deleteFromBucket(String bucket, String id) {
    try {
      DeleteObjectsRequest dor = new DeleteObjectsRequest(bucket);
      dor.withKeys(id);
      DeleteObjectsResult res = s3.deleteObjects(dor);

      Iterator<DeleteObjectsResult.DeletedObject> iter = res.getDeletedObjects().iterator();
      if (iter.hasNext()) {
        DeleteObjectsResult.DeletedObject obj = iter.next();
        return obj.getDeleteMarkerVersionId();
      }

    } catch (Exception e) {
      log.error(e);
    }
    return "";
  }

  public void deleteVersion(String id, String version) {
    deleteVersionFromBucket(bucketName, id, version);
  }

  public void deleteVersionFromBucket(String bucket, String id, String version) {
    try {
      s3.deleteVersion(bucket, id, version);
    } catch (Exception e) {
      log.error(e);
    }
  }

  public int getVersionExpiration(String bucket) {
    final int defaultTTL = 30;
    try {
      List<BucketLifecycleConfiguration.Rule> list =
          s3.getBucketLifecycleConfiguration(bucket).getRules().stream()
              // filters all "delete old version" rules
              .filter(rule -> rule.getStatus().equals(BucketLifecycleConfiguration.ENABLED)
                  && rule.getNoncurrentVersionExpiration().getDays() > 0)
              .collect(Collectors.toList());
      // for now we threat multiple version expiration rules as critical exception
      // To be able to use several rules with filters - need to check filter on per-object basis
      if (list.size() != 1) {
        throw new RuntimeException(
            "Incorrect bucket configuration. There should be 1 versions expiration rule!");
      }
      // at this point we know it's only 1 - return
      return list.get(0).getNoncurrentVersionExpiration().getDays();
    } catch (RuntimeException runtimeException) {
      log.error(runtimeException);
      throw runtimeException; // rethrow
    } catch (Exception e) {
      log.error(e);
    }
    return defaultTTL;
  }

  public byte[] get(String id) {
    return getFromBucket(bucketName, id);
  }

  public S3Object getObject(String id, Integer start, Integer end) {
    return getObjectFromBucket(bucketName, id, start, end);
  }

  public S3Object getObjectFromBucket(String bucket, String id, Integer start, Integer end) {
    GetObjectRequest gor = new GetObjectRequest(bucket, id);
    if (start != null && start >= 0) {
      if (end != null && end > start) {
        gor.setRange(start, end);
      } else {
        gor.setRange(start);
      }
    }
    return s3.getObject(gor);
  }

  public byte[] get(String id, String version) {
    return getFromBucketWithVersion(bucketName, id, version);
  }

  public void put(String id, byte[] data) {
    putToBucket(bucketName, id, data);
  }

  public String putToBucket(String bucket, String id, byte[] data) {
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(data.length);
    return s3.putObject(new PutObjectRequest(bucket, id, new ByteArrayInputStream(data), metadata))
        .getVersionId();
  }

  public String copy(String from, String to) {
    return copyInBucket(bucketName, from, to);
  }

  public String copyInBucket(String bucket, String from, String to) {
    return s3.copyObject(bucket, from, bucket, to).getVersionId();
  }

  public String promoteVersion(String key, String version) {
    return promoteVersionInBucket(bucketName, key, version);
  }

  public String promoteVersionInBucket(String bucket, String key, String version) {
    CopyObjectRequest request = new CopyObjectRequest(bucket, key, version, bucket, key);

    return s3.copyObject(request).getVersionId();
  }

  public boolean doesObjectExist(String key) {
    return doesObjectExistInBucket(bucketName, key);
  }

  public boolean doesObjectExistInBucket(String bucket, String key) {
    return s3.doesObjectExist(bucket, key);
  }

  public void putObject(PutObjectRequest putObjectRequest) {
    s3.putObject(putObjectRequest);
  }

  public ListObjectsV2Result listObjectsV2(ListObjectsV2Request request) {
    return s3.listObjectsV2(request);
  }

  public VersionListing listVersions(ListVersionsRequest request) {
    return s3.listVersions(request);
  }

  public void deleteObject(String bucket, String key) {
    s3.deleteObject(bucket, key);
  }

  public String getBucketName() {
    return bucketName;
  }

  public String getRegion() {
    return region;
  }

  public String getDownloadUrl(String key, String fileName, String versionId) {
    GeneratePresignedUrlRequest presignedUrlRequest =
        new GeneratePresignedUrlRequest(bucketName, key, HttpMethod.GET);
    if (Utils.isStringNotNullOrEmpty(versionId)) {
      presignedUrlRequest.withVersionId(versionId);
    }
    if (Utils.isStringNotNullOrEmpty(fileName)) {
      String encodedFilename = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
      presignedUrlRequest.withResponseHeaders(new ResponseHeaderOverrides()
          .withContentDisposition("attachment; filename=" + encodedFilename));
    }
    presignedUrlRequest.withExpiration(new Date(GMTHelper.utcCurrentTime() + 1000 * 60 * 30));
    URL url = s3.generatePresignedUrl(presignedUrlRequest);
    return url.toString();
  }

  public String generatePresignedUrl(String bucket, String key, boolean encode) {
    return generatePresignedUrl(bucket, key, encode, 1000 * 60 * 10);
  }

  public String generatePresignedUrl(String bucket, String key, boolean encode, long expiration) {
    URL url =
        s3.generatePresignedUrl(bucket, key, new Date(GMTHelper.utcCurrentTime() + expiration));
    if (encode) {
      return url.toString()
          .replace(url.getPath(), URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8));
    } else {
      return url.toString();
    }
  }

  public byte[] getFromBucket(String bucket, String id) {
    GetObjectRequest gor = new GetObjectRequest(bucket, id);
    try (S3Object obj = s3.getObject(gor)) {
      return IOUtils.toByteArray(obj.getObjectContent());
    } catch (Exception e) {
      log.error(e);
      return new byte[0];
    }
  }

  public byte[] getWithVersion(String id, String version) {
    return getFromBucketWithVersion(bucketName, id, version);
  }

  public byte[] getFromBucketWithVersion(String bucket, String id, String version) {
    GetObjectRequest gor = new GetObjectRequest(bucket, id, version);
    try (S3Object obj = s3.getObject(gor)) {
      return IOUtils.toByteArray(obj.getObjectContent());
    } catch (Exception e) {
      log.error(e);
      return new byte[0];
    }
  }

  public S3Object getWithVersion(String id, String version, Integer start, Integer end) {
    return getFromBucketWithVersion(bucketName, id, version, start, end);
  }

  public S3Object getFromBucketWithVersion(
      String bucket, String id, String version, Integer start, Integer end) {
    GetObjectRequest gor = new GetObjectRequest(bucket, id, version);
    if (start != null && start >= 0) {
      if (end != null && end > start) {
        gor.setRange(start, end);
      } else {
        gor.setRange(start);
      }
    }
    return s3.getObject(gor);
  }

  public ObjectMetadata getObjectMetadataOnly(String bucket, String id) {
    try {
      return s3.getObjectMetadata(bucket, id);
    } catch (Exception e) {
      log.error(e);
      return null;
    }
  }

  public ObjectMetadata getObjectMetadata(String bucket, String id) {
    GetObjectRequest gor = new GetObjectRequest(bucket, id);
    try (S3Object obj = s3.getObject(gor)) {
      try {
        return obj.getObjectMetadata();
      } catch (Exception e) {
        log.error(e);
        return null;
      } finally {
        obj.close();
      }
    } catch (Exception e) {
      log.error(e);
      return null;
    }
  }

  public String getObjectExtension(String bucket, String id) {
    ObjectMetadata objectMetadata = getObjectMetadata(bucket, id);
    if (objectMetadata != null) {
      MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
      MimeType noteMimeType = null;
      try {
        noteMimeType = allTypes.forName(objectMetadata.getContentType());
      } catch (MimeTypeException e) {
        log.error(e);
      }
      return noteMimeType != null ? noteMimeType.getExtension() : "";
    }
    return null;
  }

  public void putPublic(String bucket, String id, byte[] data) {
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(data.length);
    s3.putObject(new PutObjectRequest(bucket, id, new ByteArrayInputStream(data), metadata)
        .withCannedAcl(CannedAccessControlList.PublicRead));
  }

  public void putPublic(String bucket, String id, Buffer buffer) {
    ObjectMetadata metadata = new ObjectMetadata();
    ByteArrayInputStream stream = new ByteArrayInputStream(buffer.getBytes());
    metadata.setContentLength(bucket.getBytes().length);
    s3.putObject(new PutObjectRequest(bucket, id, stream, metadata)
        .withCannedAcl(CannedAccessControlList.PublicRead));
  }

  public String getLink(String bucket, String id) {
    return s3.getUrl(bucket, id).toExternalForm();
  }

  /**
   * Create presigned s3 url to upload large files
   *
   * @param bucket      - S3 bucket name for presigned uploaded files
   * @param key         - Key of the file to be uploaded
   * @param contentType - Content type of file
   * @return URL
   */
  public URL createPresignedUrlToUploadFile(String bucket, String key, String contentType)
      throws KudoFileException {
    try {
      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key);
      request.setMethod(HttpMethod.PUT);
      request.setContentType(contentType);
      request.setExpiration(new Date(GMTHelper.utcCurrentTime() + (1000 * 60 * 60)));
      return s3.generatePresignedUrl(request);
    } catch (Exception ex) {
      log.error("[S3 Presigned] Could not create S3 presigned URL : " + ex);
      throw new KudoFileException(
          ex.getLocalizedMessage(),
          KudoErrorCodes.UNABLE_TO_CREATE_PRESIGNED_URL,
          HttpStatus.BAD_REQUEST);
    }
  }
}
