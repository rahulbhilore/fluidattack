package com.graebert.storage.storage.link.repository;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.RangeKeyCondition;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.graebert.storage.Entities.PublicLinkErrorCodes;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.link.BaseLink;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.VersionLink;
import com.graebert.storage.storage.link.download.DownloadVersionLink;
import com.graebert.storage.storage.link.view.ViewVersionLink;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class VersionLinkRepository {
  public static final String PREFIX = "VERSION_LINK";
  public static final String INDEX_PK = "versionLinkPk";
  public static final String INDEX_SK = "versionLinkSk";

  protected final String TABLE_NAME = "";
  protected final String INDEX_NAME = "version-link-index";

  private static VersionLinkRepository instance;

  protected VersionLinkRepository() {}

  public static VersionLinkRepository getInstance() {
    if (Objects.isNull(instance)) {
      instance = new VersionLinkRepository();
    }

    return instance;
  }

  public void save(VersionLink link) {
    Dataplane.putItem(TABLE_NAME, link.toItem(), DataEntityType.CLOUDFIELD);
  }

  public void delete(VersionLink link) {
    this.delete(link.getObjectId(), link.getVersionId(), link.getToken(), link.getType());
  }

  public void delete(String fileId, String versionId, String token, LinkType type) {
    Dataplane.deleteItem(
        TABLE_NAME,
        Dataplane.pk,
        PREFIX.concat(Dataplane.hashSeparator).concat(fileId),
        Dataplane.sk,
        token
            .concat(Dataplane.hashSeparator)
            .concat(versionId)
            .concat(Dataplane.hashSeparator)
            .concat(type.toString()),
        DataEntityType.CLOUDFIELD);
  }

  public VersionLink getByToken(String fileId, String token) throws PublicLinkException {
    try {
      Item linkItem = Dataplane.query(
              TABLE_NAME,
              null,
              new QuerySpec()
                  .withHashKey(
                      Dataplane.pk,
                      VersionLinkRepository.PREFIX
                          .concat(Dataplane.hashSeparator)
                          .concat(fileId))
                  .withRangeKeyCondition(new RangeKeyCondition(Dataplane.sk)
                      .beginsWith(token.concat(Dataplane.hashSeparator))),
              DataEntityType.CLOUDFIELD)
          .iterator()
          .next();

      switch (LinkType.parse(linkItem.getString(Field.TYPE.getName()))) {
        case VERSION:
          return ViewVersionLink.fromItem(linkItem);
        case DOWNLOAD:
          return DownloadVersionLink.fromItem(linkItem);
        default:
          throw new PublicLinkException(
              "Unsupported link type ".concat(linkItem.getString(Field.TYPE.getName())),
              PublicLinkErrorCodes.PARAMETERS_ARE_INCOMPLETE);
      }
    } catch (PublicLinkException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new PublicLinkException("No such public link", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }
  }

  public VersionLink getByTokenAndVersionId(String fileId, String versionId, String token)
      throws PublicLinkException {
    try {
      Item linkItem = Dataplane.query(
              TABLE_NAME,
              null,
              new QuerySpec()
                  .withHashKey(
                      Dataplane.pk,
                      VersionLinkRepository.PREFIX
                          .concat(Dataplane.hashSeparator)
                          .concat(fileId))
                  .withRangeKeyCondition(new RangeKeyCondition(Dataplane.sk)
                      .beginsWith(token.concat(Dataplane.hashSeparator).concat(versionId))),
              DataEntityType.CLOUDFIELD)
          .iterator()
          .next();

      switch (LinkType.parse(linkItem.getString(Field.TYPE.getName()))) {
        case VERSION:
          return ViewVersionLink.fromItem(linkItem);
        case DOWNLOAD:
          return DownloadVersionLink.fromItem(linkItem);
        default:
          throw new PublicLinkException(
              "Unsupported link type ".concat(linkItem.getString(Field.TYPE.getName())),
              PublicLinkErrorCodes.PARAMETERS_ARE_INCOMPLETE);
      }
    } catch (PublicLinkException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new PublicLinkException("No such public link", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }
  }

  public List<BaseLink> getAllLinksForFileVersions(
      String externalId, String userId, String fileId) {
    return Utils.asStream(Dataplane.query(
                TABLE_NAME,
                INDEX_NAME,
                new QuerySpec()
                    .withHashKey(
                        INDEX_PK, externalId.concat(Dataplane.hashSeparator).concat(userId))
                    .withRangeKeyCondition(new RangeKeyCondition(INDEX_SK).beginsWith(fileId)),
                DataEntityType.CLOUDFIELD)
            .iterator())
        .map(item -> {
          switch (LinkType.parse(item.getString(Field.TYPE.getName()))) {
            case VERSION:
              return ViewVersionLink.fromItem(item);
            case DOWNLOAD:
            default:
              return DownloadVersionLink.fromItem(item);
          }
        })
        .collect(Collectors.toList());
  }

  public void deleteAllFileLinks(String fileId) {
    Dataplane.deleteListItemsBatch(
        Utils.asStream(Dataplane.query(
                    TABLE_NAME,
                    null,
                    new QuerySpec()
                        .withHashKey(
                            Dataplane.pk, PREFIX.concat(Dataplane.hashSeparator).concat(fileId)),
                    DataEntityType.CLOUDFIELD)
                .iterator())
            .map(item -> new PrimaryKey(
                Dataplane.pk,
                item.getString(Dataplane.pk),
                Dataplane.sk,
                item.getString(Dataplane.sk)))
            .collect(Collectors.toList()),
        TABLE_NAME,
        DataEntityType.CLOUDFIELD);
  }
}
