package com.graebert.storage.resources.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.Entities.FontType;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.resources.KudoResourceErrorCodes;
import com.graebert.storage.resources.KudoResourceException;
import com.graebert.storage.resources.ObjectFilter;
import com.graebert.storage.resources.PermissionType;
import com.graebert.storage.resources.ResourceBuffer;
import com.graebert.storage.resources.ResourceErrorIds;
import com.graebert.storage.resources.ResourceOwnerType;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.resources.dao.ResourceDAOImpl;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import kong.unirest.HttpStatus;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.jetbrains.annotations.NonNls;

public class Fonts extends BaseResource implements Resource {
  @NonNls
  public static final String address = "fonts";

  private static final OperationGroup operationGroup = OperationGroup.FONTS;
  private static final ResourceType resourceType = ResourceType.FONTS;
  public static JsonArray supportedFileTypes;
  private static S3Regional s3Regional, userS3;

  public Fonts() {}

  @Override
  public void start() throws Exception {
    super.start();

    String bucket = config.getProperties().getS3Bucket();
    String resourcesBucket = config.getProperties().getS3ResourcesBucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, resourcesBucket, region);
    userS3 = new S3Regional(config, bucket, region);
    supportedFileTypes = config.getProperties().getFontsSupportedFileTypesAsArray();

    eb.consumer(address + ".getFolderContent", this::doGetFolderContent);
    eb.consumer(address + ".createObject", this::doCreateObject);
    eb.consumer(address + ".updateObject", this::doUpdateObject);
    eb.consumer(address + ".deleteObject", this::doDeleteObject);
    eb.consumer(address + ".getObjectInfo", this::doGetObjectInfo);
    eb.consumer(address + ".getFolderPath", this::doGetFolderPath);
    eb.consumer(address + ".downloadObject", this::doDownloadObject);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-res-fonts");
  }

  public void doGetFolderContent(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      verifyAccess(message, buffer, ObjectType.FOLDER.name(), false, null, resourceType, true);

      Iterator<Item> folderIterator = getFolderContent(buffer, resourceType);

      JsonObject results = processFolderItems(
          folderIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);

      sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getFolderContent", System.currentTimeMillis() - mills);
  }

  public void doCreateObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    String objectId = Utils.generateUUID();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      buffer.setObjectId(objectId);

      Item folder = verifyAccess(
          message, buffer, buffer.getObjectType(), true, PermissionType.CREATE, resourceType, true);

      checkResourceNameExists(message, buffer, resourceType, buffer.getName());

      JsonObject objectData = makeCreateObjectData(resourceType, buffer, folder);
      if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())) {
        if (objectData.containsKey(Field.S3_PATH.getName())) {
          uploadFileToS3(
              s3Regional, buffer.getFileContent(), objectData.getString(Field.S3_PATH.getName()));
          log.info("New font file uploaded with id - " + objectId);
        }
        additionalFontProcessing(message, buffer);
        if (Objects.nonNull(buffer.getFontFamilyNames())) {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("createFontFamilyObjects")
              .runWithoutSegment(() -> createFontFamilyObjects(buffer));
        }
      }
      createObject(buffer, resourceType, objectData);

      sendOK(segment, message, new JsonObject().put(Field.OBJECT_ID.getName(), objectId), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("createObject", System.currentTimeMillis() - mills);
  }

  public void doUpdateObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);

      Item object = verifyAccess(
          message,
          buffer,
          buffer.getObjectType(),
          true,
          PermissionType.UPDATE,
          resourceType,
          false);

      if (Utils.isStringNotNullOrEmpty(buffer.getName())) {
        checkResourceNameExists(message, buffer, resourceType, buffer.getName());
      }
      JsonObject objectData = makeUpdateObjectData(buffer, object);
      Item updatedObject = null;
      boolean isObjectUpdated = false;
      if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())) {
        isObjectUpdated =
            updateS3File(segment, message, s3Regional, object, objectData, buffer.getFileContent());
        if (Objects.nonNull(buffer.getFileContent())) {
          additionalFontProcessing(message, buffer);
          if (Objects.nonNull(buffer.getFontFamilyNames())) {
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .runWithoutSegment(() -> {
                  String fileName = object.getString(Field.FILE_NAME_C.getName());
                  // delete old font family objects
                  if (Utils.isStringNotNullOrEmpty(fileName)
                      && Extensions.getExtension(fileName)
                          .substring(1)
                          .equalsIgnoreCase(FontType.SHX.name())) {
                    deleteObject(
                        resourceType
                            + Dataplane.hashSeparator
                            + buffer.getOwnerType()
                            + Dataplane.hashSeparator
                            + object.getString(Field.FILE_NAME_C.getName()),
                        buffer.getObjectId());
                  } else {
                    List<Map<String, Object>> fontInfo = object.getList("fontInfo");
                    fontInfo.forEach(info -> deleteObject(
                        resourceType
                            + Dataplane.hashSeparator
                            + buffer.getOwnerType()
                            + Dataplane.hashSeparator
                            + info.get("fontFamily"),
                        buffer.getObjectId()));
                  }
                  createFontFamilyObjects(buffer);
                });
          }
        }
      }
      if (objectData.isEmpty()) {
        if (isObjectUpdated) {
          updatedObject = object;
        } else {
          throw new KudoResourceException(
              Utils.getLocalizedString(message, ResourceErrorIds.NOTHING_TO_UPDATE.id),
              KudoResourceErrorCodes.NOTHING_TO_UPDATE,
              HttpStatus.PRECONDITION_FAILED,
              ResourceErrorIds.NOTHING_TO_UPDATE.id);
        }
      }
      if (Objects.isNull(updatedObject)) {
        updatedObject = updateObject(
            object.getString(Dataplane.pk),
            object.getString(Dataplane.sk),
            objectData,
            resourceType,
            buffer.getFontInfo());
      }
      sendOK(segment, message, itemToJson(updatedObject, buffer.getUserId(), false), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("updateObject", System.currentTimeMillis() - mills);
  }

  public void doDeleteObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);

      Item object = verifyAccess(
          message,
          buffer,
          buffer.getObjectType(),
          true,
          PermissionType.DELETE,
          resourceType,
          false);

      if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("deleteFileFromS3")
            .runWithoutSegment(() -> deleteFileFromS3(s3Regional, object, buffer.getObjectId()));
      }
      deleteObject(object.getString(Dataplane.pk), object.getString(Dataplane.sk));
      sendOK(segment, message, mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("deleteObject", System.currentTimeMillis() - mills);
  }

  public void doGetObjectInfo(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object =
          verifyAccess(message, buffer, buffer.getObjectType(), false, null, resourceType, false);
      sendOK(segment, message, itemToJson(object, buffer.getUserId(), true), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      verifyAccess(message, buffer, buffer.getObjectType(), false, null, resourceType, false);
      JsonArray path = getFolderPath(message, buffer, resourceType);

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), path), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getFolderPath", System.currentTimeMillis() - mills);
  }

  public void doDownloadObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    boolean recursive = jsonObject.getBoolean(Field.RECURSIVE.getName());
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object =
          verifyAccess(message, buffer, buffer.getObjectType(), false, null, resourceType, false);
      if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())) {
        byte[] content = getFileFromS3(message, s3Regional, object, buffer.getObjectId());
        finishGetFile(message, buffer.getDownloadToken(), content);
      } else {
        ByteArrayOutputStream bos = downloadFolder(
            message, s3Regional, buffer.getDownloadToken(), buffer, recursive, resourceType);
        Item request = ZipRequests.getZipRequest(
            buffer.getUserId(), buffer.getObjectId(), buffer.getDownloadToken());
        finishGetFolder(message, userS3, buffer.getDownloadToken(), request, bos);
      }
      log.info("Download is complete for" + buffer.getObjectType().toLowerCase() + " - "
          + buffer.getObjectId());
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("downloadObject", System.currentTimeMillis() - mills);
  }

  private void additionalFontProcessing(Message<JsonObject> message, ResourceBuffer buffer)
      throws KudoResourceException {
    List<Map<String, Object>> fontInfo;
    List<String> fontFamilyNames = new ArrayList<>();
    // TTF file: parse and generate fontInfo map for font in ttf
    // and put familyFontName to array
    if (buffer.getFileType().equalsIgnoreCase(FontType.TTF.name())) {
      fontInfo = new ArrayList<>();
      try (RandomAccessRead randomAccessRead =
          new RandomAccessReadBuffer(buffer.getFileContent())) {
        fontInfo.add(getFontInfoMap(new TTFParser().parse(randomAccessRead), 0, fontFamilyNames));
      } catch (IOException e) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_READ_FONT_FILE.id),
            KudoResourceErrorCodes.INVALID_FONT_DATA,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_READ_FONT_FILE.id);
      }
    }
    // TTC file: parse and generate fontInfo maps for each font in ttc collection
    // and put all familyFontNames to array
    else if (buffer.getFileType().equalsIgnoreCase(FontType.TTC.name())) {
      fontInfo = new ArrayList<>();
      try {
        AtomicInteger counter = new AtomicInteger(0);
        new TrueTypeCollection(new ByteArrayInputStream(buffer.getFileContent()))
            .processAllFonts(trueTypeFont ->
                fontInfo.add(getFontInfoMap(trueTypeFont, counter.getAndAdd(1), fontFamilyNames)));
      } catch (IOException e) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_READ_FONT_FILE.id),
            KudoResourceErrorCodes.INVALID_FONT_DATA,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_READ_FONT_FILE.id);
      }
    }
    // SHX file: don't need any extra info, just add filename to FamilyFont
    else if (buffer.getFileType().equalsIgnoreCase(FontType.SHX.name())) {
      fontInfo = null;
      fontFamilyNames.add(buffer.getFileName());
    } else {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.FILE_NOT_SUPPORTED_FOR_FONTS.id),
          KudoResourceErrorCodes.FILE_NOT_SUPPORTED,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.FILE_NOT_SUPPORTED_FOR_FONTS.id);
    }
    buffer.setFontInfo(fontInfo);
    buffer.setFontFamilyNames(fontFamilyNames);
  }

  private Map<String, Object> getFontInfoMap(
      TrueTypeFont trueTypeFont, int index, List<String> fontFamilyNames) {
    Map<String, Object> map = new HashMap<>();
    try {
      fontFamilyNames.add(trueTypeFont.getNaming().getFontFamily());

      map.put("index", index);
      map.put("fontFamily", trueTypeFont.getNaming().getFontFamily());
      map.put("style", trueTypeFont.getNaming().getFontSubFamily());
      map.put("weight", trueTypeFont.getOS2Windows().getWeightClass());
      map.put("bold", (trueTypeFont.getOS2Windows().getFsSelection() & 32) == 32);
      map.put("italic", (trueTypeFont.getOS2Windows().getFsSelection() & 1) == 1);
    } catch (IOException e) {
      return null;
    }
    return map;
  }

  private void createFontFamilyObjects(ResourceBuffer buffer) {
    List<Item> items = new ArrayList<>();
    ResourceOwnerType ownerType = ResourceOwnerType.getType(buffer.getOwnerType());
    if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
      buffer.setOwnerId(emptyString);
    }
    buffer.getFontFamilyNames().forEach(fontName -> {
      Item object = new Item()
          .withString(Field.OBJECT_ID.getName(), buffer.getObjectId())
          .withString(
              Dataplane.pk,
              resourceType.name()
                  + Dataplane.hashSeparator
                  + buffer.getOwnerType()
                  + Dataplane.hashSeparator
                  + fontName)
          .withString(Dataplane.sk, buffer.getObjectId())
          .withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime())
          .withString("fontName", fontName);

      if (buffer.getParent().equals(BaseResource.sharedFolderId)
          && Utils.isStringNotNullOrEmpty(buffer.getUserId())) {
        object.withString(Field.USER_ID.getName(), buffer.getUserId());
      }

      if (Utils.isStringNotNullOrEmpty(buffer.getOwnerId())) {
        object.withString(Field.OWNER_ID.getName(), buffer.getOwnerId());
      }

      if (Objects.nonNull(buffer.getFontInfo())) {
        object.withList("fontInfo", buffer.getFontInfo());
      }
      items.add(object);
    });
    ResourceDAOImpl.batchWriteListItems(
        items, ResourceDAOImpl.resourcesTable, DataEntityType.RESOURCES);
  }
}
