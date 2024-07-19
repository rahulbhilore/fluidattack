package com.graebert.storage.Entities;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

public class Font extends Dataplane {
  public static final String pkMainPrefix = "FONTS#";
  private static final String FONTS_TABLE = TableName.MAIN.name;
  private static final String pkUserPrefix = pkMainPrefix.concat("userfont#");
  private static final String pkCompanyPrefix = pkMainPrefix.concat("companyfont#");
  public static S3Regional s3Regional;
  public static String urlPrefix;
  private String authorId;
  private String fontId;
  private String filename;
  private String filenameLower;
  private long size;
  private long uploadDate;
  private FontType type;
  private byte[] bytes;
  private String s3key;
  private final boolean isCompanyFont;
  private ArrayList<String> fontFamilyNames;
  private List<Map<String, Object>> fontInfo = null;
  // underlying DB item
  private Item dbLink;
  // DB keys
  private String pkAuthorId;
  private String sk;

  // constructor for new Font
  public Font(String authorId, String filename, boolean isCompanyFont, byte[] bytes)
      throws FontException {
    this.type = FontType.getFontTypeByFilename(filename);

    this.authorId = authorId;
    this.fontId = Utils.generateUUID();
    this.filename = filename;
    this.filenameLower = filename.toLowerCase();
    this.bytes = bytes;
    this.size = bytes.length;
    this.uploadDate = GMTHelper.utcCurrentTime();
    this.s3key = authorId.concat("/").concat(fontId);
    this.isCompanyFont = isCompanyFont;
    this.fontFamilyNames = new ArrayList<>();
    if (isCompanyFont) {
      this.pkAuthorId = pkCompanyPrefix.concat(authorId);
    } else {
      this.pkAuthorId = pkUserPrefix.concat(authorId);
    }
    this.sk = this.fontId;

    // TTF file: parse and generate font_info map for font in ttf
    // and put familyFontName to array
    if (type.equals(FontType.TTF)) {
      fontInfo = new ArrayList<>();
      try (RandomAccessRead randomAccessRead = new RandomAccessReadBuffer(bytes)) {
        fontInfo.add(getFontInfoMap(new TTFParser().parse(randomAccessRead), 0));
      } catch (IOException e) {
        throw new FontException("Font file cant be read", FontErrorCodes.INVALID_FONT_DATA);
      }
    }

    // TTC file: parse and generate font_info maps for each font in ttc collection
    // and put all familyFontNames to array
    else if (type.equals(FontType.TTC)) {
      fontInfo = new ArrayList<Map<String, Object>>();

      try {
        AtomicInteger counter = new AtomicInteger(0);
        new TrueTypeCollection(new ByteArrayInputStream(bytes)).processAllFonts(trueTypeFont -> {
          fontInfo.add(getFontInfoMap(trueTypeFont, counter.getAndAdd(1)));
        });
      } catch (IOException e) {
        throw new FontException("Font file cant be read", FontErrorCodes.INVALID_FONT_DATA);
      }
    }

    // SHX file: dont need any extra info, just add filename to FamilyFont
    else if (type.equals(FontType.SHX)) {
      fontInfo = null;
      fontFamilyNames.add(filename);
    }

    // other files
    else {
      throw new FontException("Unsupported font type", FontErrorCodes.UNSUPPORTED_FONT);
    }
  }

  private Font(Item font) throws FontException {
    try {
      this.authorId = font.getString("authorId");
      this.fontId = font.getString("fontId");
      this.filename = font.getString(Field.FILE_NAME.getName());
      this.filenameLower = font.getString("filenameLower");
      this.size = font.getLong(Field.SIZE.getName());
      this.isCompanyFont = font.getBOOL("isCompanyFont");
      this.uploadDate = font.getLong(Field.UPLOAD_DATE.getName());
      this.type = FontType.getFontTypeByExtension(font.getString(Field.TYPE.getName()));
      this.s3key = font.getString("s3key");

      this.fontFamilyNames = new ArrayList<>();
      if (this.type != FontType.SHX) {
        this.fontInfo = font.getList("font_info");
        fontInfo.forEach(map -> {
          this.fontFamilyNames.add((String) map.get("fontFamily"));
        });
      }

      this.dbLink = font;
      if (isCompanyFont) {
        this.pkAuthorId = pkCompanyPrefix.concat(font.getString("authorId"));
      } else {
        this.pkAuthorId = pkUserPrefix.concat(font.getString("authorId"));
      }
      this.sk = font.getString("fontId");

      this.bytes = s3Regional.get(s3key);

    } catch (Exception e) {
      throw new FontException("Font not found", FontErrorCodes.FONT_NOT_FOUND);
    }
  }

  public static void init(ServerConfig config) {
    String url = config.getProperties().getUrl();
    urlPrefix = url.concat("api/fonts/");

    String bucket = config.getProperties().getFontsBucket();
    String region = config.getProperties().getFontsRegion();
    s3Regional = new S3Regional(config, bucket, region);
  }

  public static Font getFontById(String authorId, String fontId, boolean isCompanyFont)
      throws FontException {
    String pk = isCompanyFont ? pkCompanyPrefix + authorId : pkUserPrefix + authorId;

    Item font = getItemFromDB(
        FONTS_TABLE, Field.PK.getName(), pk, Field.SK.getName(), fontId, DataEntityType.RESOURCES);

    if (font != null) {
      return new Font(font);
    }

    throw new FontException("Font not found", FontErrorCodes.FONT_NOT_FOUND);
  }

  public static JsonObject getFontsJson(String authorId, boolean isCompanyFont) {
    String pk = isCompanyFont ? pkCompanyPrefix + authorId : pkUserPrefix + authorId;
    JsonArray fonts = new JsonArray();
    for (Item item : query(
        FONTS_TABLE,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk")
            .withValueMap(new ValueMap().withString(":pk", pk)),
        DataEntityType.RESOURCES)) {

      fonts.add(formatToJson(item));
    }

    return new JsonObject().put("fonts", fonts);
  }

  public static JsonObject formatToJson(Item item) {
    JsonObject formatted = new JsonObject()
        .put(Field.FILE.getName(), item.getString(Field.FILE_NAME.getName()))
        .put(Field.ID.getName(), item.getString("fontId"))
        .put(Field.SIZE.getName(), item.getLong(Field.SIZE.getName()))
        .put(Field.URL.getName(), urlPrefix.concat(item.getString("fontId")));

    if (item.hasAttribute("font_info")) {
      formatted.put("faces", new JsonArray(item.getJSON("font_info")));
    } else
    // DK: just for data consistency.
    {
      formatted.put("faces", new JsonArray());
    }

    return formatted;
  }

  private Map<String, Object> getFontInfoMap(TrueTypeFont trueTypeFont, int index) {
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

  public boolean deleteFont() throws FontException {
    if (type.equals(FontType.TTF) || type.equals(FontType.TTC)) {
      deleteItem(
          FONTS_TABLE,
          Field.PK.getName(),
          pkAuthorId,
          Field.SK.getName(),
          sk,
          DataEntityType.RESOURCES);

      fontFamilyNames.forEach(name -> {
        deleteItem(
            FONTS_TABLE,
            Field.PK.getName(),
            isCompanyFont ? pkCompanyPrefix.concat(name) : pkUserPrefix.concat(name),
            Field.SK.getName(),
            sk,
            DataEntityType.RESOURCES);
      });

      s3Regional.delete(s3key);

      return true;
    } else if (type.equals(FontType.SHX)) {
      deleteItem(
          FONTS_TABLE,
          Field.PK.getName(),
          pkAuthorId,
          Field.SK.getName(),
          sk,
          DataEntityType.RESOURCES);
      deleteItem(
          FONTS_TABLE,
          Field.PK.getName(),
          isCompanyFont ? pkCompanyPrefix.concat(filename) : pkUserPrefix.concat(filename),
          Field.SK.getName(),
          sk,
          DataEntityType.RESOURCES);

      s3Regional.delete(s3key);

      return true;
    }

    return false;
  }

  public boolean createOrUpdate() throws FontException {
    List<Item> arr = new ArrayList<>();

    Item itemToInsertUserIdPk = new Item();
    itemToInsertUserIdPk
        .withPrimaryKey(Field.PK.getName(), pkAuthorId, Field.SK.getName(), sk)
        .withString("authorId", authorId)
        .withString("fontId", fontId)
        .withString(Field.FILE_NAME.getName(), filename)
        .withString("filenameLower", filenameLower)
        .withBoolean("isCompanyFont", isCompanyFont)
        .withLong(Field.SIZE.getName(), size)
        .withLong(Field.UPLOAD_DATE.getName(), uploadDate)
        .withString(Field.TYPE.getName(), type.name())
        .withString("s3key", s3key);
    if (fontInfo != null) {
      itemToInsertUserIdPk.withList("font_info", fontInfo);
    }

    // add main write with pk = userfont#:userId
    arr.add(itemToInsertUserIdPk);
    dbLink = itemToInsertUserIdPk;

    // add duplicating writes with pk = userfont#:fontFamilyName
    // only 1 for ttf and ttc.length for ttc
    fontFamilyNames.forEach(fontName -> {
      Item additional = new Item();
      additional
          .withPrimaryKey(
              Field.PK.getName(),
              isCompanyFont ? pkCompanyPrefix + fontName : pkUserPrefix + fontName,
              Field.SK.getName(),
              sk)
          .withString("authorId", authorId)
          .withString("fontId", fontId)
          .withString(Field.FILE_NAME.getName(), filename)
          .withString("filenameLower", filenameLower)
          .withLong(Field.SIZE.getName(), size)
          .withLong(Field.UPLOAD_DATE.getName(), uploadDate)
          .withString(Field.TYPE.getName(), type.name())
          .withString("s3key", s3key);
      if (fontInfo != null) {
        additional.withList("font_info", fontInfo);
      }
      arr.add(additional);
    });

    try {
      batchWriteListItems(arr, FONTS_TABLE, DataEntityType.RESOURCES);
      s3Regional.put(s3key, bytes);
      return true;
    } catch (Exception e) {
      throw new FontException("Bad Dynamodb connection", FontErrorCodes.BAD_DB_CONNECTION);
    }
  }

  // getters & setters

  public String getAuthorId() {
    return authorId;
  }

  public void setAuthorId(String authorId) {
    this.authorId = authorId;
  }

  public String getFontId() {
    return fontId;
  }

  public void setFontId(String fontId) {
    this.fontId = fontId;
  }

  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

  public String getFilenameLower() {
    return filenameLower;
  }

  public void setFilenameLower(String filenameLower) {
    this.filenameLower = filenameLower;
  }

  public long getSize() {
    return size;
  }

  public void setSize(long size) {
    this.size = size;
  }

  public long getUploadDate() {
    return uploadDate;
  }

  public void setUploadDate(long uploadDate) {
    this.uploadDate = uploadDate;
  }

  public FontType getType() {
    return type;
  }

  public void setType(FontType type) {
    this.type = type;
  }

  public byte[] getBytes() {
    return bytes;
  }

  public void setBytes(byte[] bytes) {
    this.bytes = bytes;
  }

  public String getS3key() {
    return s3key;
  }

  public void setS3key(String s3key) {
    this.s3key = s3key;
  }

  public ArrayList<String> getFontFamilyNames() {
    return fontFamilyNames;
  }

  public void setFontFamilyNames(ArrayList<String> fontFamilyNames) {
    this.fontFamilyNames = fontFamilyNames;
  }

  public List<Map<String, Object>> getFontInfo() {
    return fontInfo;
  }

  public void setFontInfo(List<Map<String, Object>> fontInfo) {
    this.fontInfo = fontInfo;
  }

  public String getPkAuthorId() {
    return pkAuthorId;
  }

  public void setPkAuthorId(String pkUserId) {
    this.pkAuthorId = pkAuthorId;
  }

  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }
}
