package com.graebert.storage.util.message;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.gridfs.FileBuffer;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.xray.XRayEntityUtils;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;

/**
 * Class provides static methods to generate / parse Message
 * We support 2 types of message:
 * 1. Buffer - transfers both JsonObject and byte[] - data of file/template/etc.
 *      of type [
 *        Integer (length of jsonObject),
 *        Byte[] (encoded JsonObject of specified length),
 *        Byte[] (object raw data)
 *      ]
 * 2. JsonObject - default case to transfer any other data
 */
public class MessageUtils {
  /**
   * Parses incoming Message of Buffer or JsonObject
   * @param message - message from EventBus consumer
   * @return ParsedMessage
   * @param <T> is JsonObject or Buffer
   */
  public static <T> ParsedMessage parse(Message<T> message) {
    if (message.body() instanceof Buffer) {
      return parseBuffer((Buffer) message.body(), true);
    } else {
      return new ParsedMessage((JsonObject) message.body());
    }
  }

  /**
   * Parses any buffer
   * @param buffer - any Buffer
   * @return ParsedMessage
   */
  public static ParsedMessage parseBuffer(Buffer buffer, boolean parseContent) {
    int len = buffer.getInt(0);
    byte from = 4;
    byte[] jsonBytes = buffer.getBytes(from, from + len);
    JsonObject jsonObject = new JsonObject(Utils.decode(jsonBytes));
    if (parseContent) {
      int from1 = from + len;
      byte[] content = buffer.getBytes(from1, buffer.length());
      jsonObject.put("length", content.length);
      return new ParsedMessage(jsonObject, content);
    }
    return new ParsedMessage(jsonObject);
  }

  /**
   * Parses just Json from passed Buffer
   * @param buffer - any Buffer
   * @return ParsedMessage
   */
  public static ParsedMessage parseJsonObject(Buffer buffer) {
    return new ParsedMessage(new JsonObject(
        new String(buffer.getBytes(4, 4 + buffer.getInt(0)), StandardCharsets.UTF_8)));
  }

  /**
   * Generates new Buffer for message
   * @param segment - Entity currentSegment
   * @param jsonObject - JsonObject json to put
   * @param data - byte[] data to put
   * @param locale - String locale key to put
   * @return Buffer
   */
  public static Buffer generateBuffer(
      Entity segment, JsonObject jsonObject, byte[] data, String locale) {
    putSegmentDataToJsonObject(segment, jsonObject);

    if (locale != null) {
      jsonObject.put(Field.LOCALE.getName(), locale);
    } else if (!jsonObject.containsKey(Field.LOCALE.getName())) {
      jsonObject.put(Field.LOCALE.getName(), "en");
    }

    return composeBuffer(jsonObject, data);
  }

  /**
   * Generates new Buffer for message from passed FileBuffer
   * @param segment - Entity currentSegment
   * @param fileBuffer - FileBuffer to put
   * @return Buffer
   */
  public static Buffer generateBuffer(Entity segment, FileBuffer fileBuffer) {
    return generateBuffer(
        segment,
        putSegmentDataToJsonObject(segment, fileBuffer.bufferToJson()),
        fileBuffer.getData(),
        fileBuffer.getLocale());
  }

  /**
   * Adds required SegmentData (traceId, parent) to specified Buffer
   * @param segment - existing Segment
   * @param buffer - existing Buffer
   * @return new Buffer with merged data, or same buffer if no data should be merged
   */
  public static Buffer putSegmentDataToBuffer(Entity segment, Buffer buffer) {
    if (segment != null) {
      return mergeDataInBuffer(buffer, XRayEntityUtils.getRequiredSegmentDataObject(segment));
    }

    return buffer;
  }

  /**
   * Adds required SegmentData (traceId, parent) to specified JsonObject
   * @param segment - existing Segment
   * @param jsonObject - existing JsonObject
   * @return json object with required info, or old jsonObject
   */
  public static JsonObject putSegmentDataToJsonObject(Entity segment, JsonObject jsonObject) {
    if (segment != null) {
      return jsonObject.mergeIn(XRayEntityUtils.getRequiredSegmentDataObject(segment));
    }

    return jsonObject;
  }

  /**
   * returns new Buffer with merged specified JsonObject inside Buffer's jsonObject
   * @param buffer - existing Buffer
   * @param objectToMerge - JsonObject to merge
   * @return new Buffer with merged data
   */
  private static Buffer mergeDataInBuffer(Buffer buffer, JsonObject objectToMerge) {
    ParsedMessage message = parseBuffer(buffer, true);

    return composeBuffer(
        message.getJsonObject().mergeIn(objectToMerge), message.getContentAsByteArray());
  }

  /**
   * Creates new Buffer with provided json and byte-data
   * @param jsonObject - JsonObject to compose
   * @param data - byte[] to compose
   * @return new Buffer
   */
  private static Buffer composeBuffer(JsonObject jsonObject, byte[] data) {
    byte[] json = jsonObject.encode().getBytes(StandardCharsets.UTF_8);

    Buffer buffer = Buffer.buffer();
    buffer.appendInt(json.length);
    buffer.appendBytes(json);

    if (data != null) {
      buffer.appendBytes(data);
    }

    return buffer;
  }

  private MessageUtils() {}
}
