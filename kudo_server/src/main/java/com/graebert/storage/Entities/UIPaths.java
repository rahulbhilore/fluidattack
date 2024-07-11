package com.graebert.storage.Entities;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.resources.OldResourceTypes;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.util.Field;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

public class UIPaths {
  public static String getFileUrl(String instanceURL, String fileId) {
    return instanceURL + "file/" + fileId;
  }

  public static String getConflictingFileUrl(String instanceURL, String urlPostFix) {
    return instanceURL + urlPostFix;
  }

  public static String getFolderUrl(String instanceURL, String folderId) {
    return instanceURL + "files/" + folderId;
  }

  public static String getFolderUrl(
      String instanceURL, StorageType storageType, String accountId, String folderId) {
    return instanceURL + "files/" + StorageType.getShort(storageType) + "/" + accountId + "/"
        + folderId;
  }

  public static String getResetPasswordURL(String instanceURL, String userId, String hash) {
    return instanceURL + "notify?mode=reset&key=" + userId + "&hash=" + hash;
  }

  public static String getEmailChangeURL(
      String instanceURL, String userId, String newEmail, String secretKey)
      throws BadPaddingException, UnsupportedEncodingException, IllegalBlockSizeException,
          NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException {
    return instanceURL + "notify?mode=confirmEmail&key=" + userId + "&hash="
        + EncryptHelper.encrypt(newEmail, secretKey);
  }

  public static String getRegistrationConfirmationURL(
      String instanceURL, String userId, String hash) {
    return instanceURL + "notify?mode=confirm&key=" + userId + "&hash=" + hash;
  }

  public static String getActivationURL(String instanceURL, String userId) {
    return instanceURL + "notify?mode=activate&key=" + userId;
  }

  public static String getUsersPageURL(String instanceURL) {
    return instanceURL + Field.USERS.getName();
  }

  public static String getResourceURL(
      String instanceURL, String resourceParentId, OldResourceTypes resourceType) {
    String resourceTypePart = "";
    if (resourceType.equals(OldResourceTypes.BLOCK)
        || resourceType.equals(OldResourceTypes.BLOCKLIBRARY)) {
      resourceTypePart = Field.BLOCKS.getName();
    }
    if (!resourceParentId.equals(Field.MINUS_1.getName())) {
      return instanceURL + "resources/" + resourceTypePart + "/" + resourceParentId;
    }
    return instanceURL + "resources/" + resourceTypePart;
  }
}
