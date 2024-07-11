package com.graebert.storage.util.message;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.StreamHelper;
import com.graebert.storage.vertx.BaseVerticle;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class ParsedMessage {
  private final JsonObject jsonObject;
  private final byte[] content;

  public ParsedMessage(JsonObject jsonObject, byte[] content) {
    this.jsonObject = jsonObject;
    this.content = content;
  }

  public ParsedMessage(JsonObject jsonObject) {
    this.jsonObject = jsonObject;
    this.content = null;
  }

  public JsonObject getJsonObject() {
    return jsonObject;
  }

  public boolean hasAnyContent() {
    return this.hasByteArrayContent() || this.hasInputStreamContent();
  }

  public boolean hasByteArrayContent() {
    return this.content != null && this.content.length > 0;
  }

  public boolean hasInputStreamContent() {
    return this.jsonObject.containsKey(Field.DOWNLOAD_URL.getName());
  }

  public byte[] getContentAsByteArray() {
    return content;
  }

  public InputStream getContentAsInputStream() throws ParsedMessageException {
    try {
      if (this.hasInputStreamContent()) {
        return StreamHelper.getStreamFromDownloadUrl(BaseVerticle.HTTP_CLIENT, this.jsonObject);
      } else if (this.hasByteArrayContent()) {
        this.jsonObject.put(Field.FILE_SIZE.getName(), this.getContentAsByteArray().length);
        return new ByteArrayInputStream(this.getContentAsByteArray());
      }
    } catch (Exception exception) {
      throw new ParsedMessageException(exception.getMessage());
    }
    throw new ParsedMessageException("NoFileContentToUpload");
  }

  public int getFileSizeFromJsonObject() {
    return this.jsonObject.getInteger(Field.FILE_SIZE.getName());
  }
}
