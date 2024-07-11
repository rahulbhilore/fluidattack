package com.graebert.storage.storage.link.repository;

import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.storage.link.VersionLink;
import java.util.List;

public interface TypedVersionLinkRepository<T extends VersionLink> {
  T get(String fileId, String versionId, String token) throws PublicLinkException;

  T getUserLink(String userId, String externalId, String fileId, String versionId, String sheetId)
      throws PublicLinkException;

  List<T> getUserLinks(String externalId, String userId, String fileId);
}
