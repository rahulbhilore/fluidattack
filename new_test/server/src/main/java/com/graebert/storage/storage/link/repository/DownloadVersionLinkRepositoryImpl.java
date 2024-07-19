package com.graebert.storage.storage.link.repository;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.RangeKeyCondition;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.graebert.storage.Entities.PublicLinkErrorCodes;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.download.DownloadVersionLink;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class DownloadVersionLinkRepositoryImpl extends VersionLinkRepository
    implements TypedVersionLinkRepository<DownloadVersionLink> {
  private static DownloadVersionLinkRepositoryImpl instance;

  private DownloadVersionLinkRepositoryImpl() {}

  public static DownloadVersionLinkRepositoryImpl getInstance() {
    if (Objects.isNull(instance)) {
      instance = new DownloadVersionLinkRepositoryImpl();
    }

    return instance;
  }

  @Override
  public DownloadVersionLink get(String fileId, String versionId, String token)
      throws PublicLinkException {
    Item linkItem = Dataplane.getItem(
        TABLE_NAME,
        Dataplane.pk,
        PREFIX.concat(Dataplane.hashSeparator).concat(fileId),
        Dataplane.sk,
        token
            .concat(Dataplane.hashSeparator)
            .concat(versionId)
            .concat(Dataplane.hashSeparator)
            .concat(LinkType.DOWNLOAD.toString()),
        true,
        DataEntityType.CLOUDFIELD);

    if (Objects.isNull(linkItem)) {
      throw new PublicLinkException(
          "No such version public link", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }

    return DownloadVersionLink.fromItem(linkItem);
  }

  @Override
  public DownloadVersionLink getUserLink(
      String userId, String externalId, String fileId, String versionId, String sheetId) {
    try {
      return DownloadVersionLink.fromItem(Dataplane.query(
              TABLE_NAME,
              INDEX_NAME,
              new QuerySpec()
                  .withHashKey(
                      INDEX_PK, externalId.concat(Dataplane.hashSeparator).concat(userId))
                  .withRangeKeyCondition(new RangeKeyCondition(INDEX_SK)
                      .eq(fileId
                          .concat(Dataplane.hashSeparator)
                          .concat(LinkType.DOWNLOAD.toString())
                          .concat(Dataplane.hashSeparator)
                          .concat(versionId)
                          .concat(
                              Objects.nonNull(sheetId)
                                  ? Dataplane.hashSeparator.concat(sheetId)
                                  : Dataplane.emptyString))),
              DataEntityType.CLOUDFIELD)
          .iterator()
          .next());
    } catch (Exception exception) {
      return null;
    }
  }

  @Override
  public List<DownloadVersionLink> getUserLinks(String externalId, String userId, String fileId) {
    return StreamSupport.stream(
            Dataplane.query(
                    TABLE_NAME,
                    INDEX_NAME,
                    new QuerySpec()
                        .withHashKey(
                            INDEX_PK, externalId.concat(Dataplane.hashSeparator).concat(userId))
                        .withRangeKeyCondition(new RangeKeyCondition(INDEX_SK)
                            .beginsWith(fileId
                                .concat(Dataplane.hashSeparator)
                                .concat(LinkType.DOWNLOAD.toString()))),
                    DataEntityType.CLOUDFIELD)
                .spliterator(),
            true)
        .map(DownloadVersionLink::fromItem)
        .collect(Collectors.toList());
  }
}
