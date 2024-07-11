package com.graebert.storage.storage.link.repository;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.AWSXRay;
import com.graebert.storage.Entities.PublicLinkErrorCodes;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.link.BaseLink;
import com.graebert.storage.storage.link.LinkConfiguration;
import com.graebert.storage.users.LicensingService;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Objects;
import kong.unirest.HttpResponse;

public class LinkService extends Dataplane {
  private static final String NONCE_TABLE = TableName.TEMP.name;

  public static void setPassword(BaseLink link, String passwordHeader) throws PublicLinkException {
    HashMap<String, String> values = parsePasswordHeader(passwordHeader);

    final String nonce = values.get(Dataplane.nonce);
    final String response = values.get("response");
    final String ha1 = values.get("ha1");
    // let's not share it - just use a constant on each platform
    final String uri = "GET:/files/link";

    if (!Utils.isStringNotNullOrEmpty(nonce)) {
      throw new PublicLinkException("Nonce is required", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
    } else {
      // let's check that the password is encoded correctly
      Item nonceDb = getItem(
          NONCE_TABLE,
          pk,
          Dataplane.nonce.concat(Dataplane.hashSeparator).concat(nonce),
          sk,
          nonce,
          false,
          DataEntityType.CLOUDFIELD);

      // if such nonce exists in the DB - means that it was most likely generated on the FL server
      if (nonceDb != null) {

        // just remove nonce from the DB to make sure it cannot be reused
        deleteItem(
            NONCE_TABLE,
            pk,
            Dataplane.nonce.concat(Dataplane.hashSeparator).concat(nonce),
            sk,
            nonce,
            DataEntityType.CLOUDFIELD);

        try {
          String ha2 = EncryptHelper.md5Hex(uri);
          String serverResponse = EncryptHelper.md5Hex(ha1 + ":" + nonce + ":" + ha2);
          if (!serverResponse.equals(response)) {
            throw new PublicLinkException(
                "Digest responses aren't equal", PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
          } else {
            link.setPassword(
                EncryptHelper.encrypt(ha1, LinkConfiguration.getInstance().getSecretKey()));
          }
        } catch (Exception encryptionException) {
          log.error(encryptionException);
          throw new PublicLinkException(
              "Couldn't encrypt the password: " + encryptionException.getMessage(),
              PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
        }

      } else {
        throw new PublicLinkException(
            "Nonce isn't found in DB", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
      }
    }
  }

  public static boolean checkLicense(String userEmail) throws PublicLinkException {
    if (!LinkConfiguration.getInstance().isEnterprise()) {
      HttpResponse<String> licenseResponse;

      String traceId = null;
      try {
        traceId = AWSXRay.getGlobalRecorder().getCurrentSegment().getTraceId().toString();
      } catch (Exception e) {
        log.warn("Cannot get traceId", e);
      }
      try {
        licenseResponse = LicensingService.hasAnyKudo(userEmail, null, traceId);
      } catch (Exception e) {
        throw new PublicLinkException(
            "Unable to check user's license", PublicLinkErrorCodes.UNABLE_TO_CHECK_LICENSE);
      }

      // LS fallback
      if (Objects.isNull(licenseResponse)) {
        return true;
      }
      boolean isLicenseExpired = licenseResponse.getStatus() != 200;
      if (isLicenseExpired) {
        throw new PublicLinkException(
            "License expired", PublicLinkErrorCodes.KUDO_LICENSE_HAS_EXPIRED);
      } else {
        return true;
      }
    }
    return true;
  }

  public static void assertPasswordIsCorrect(BaseLink link, final String passwordHeader)
      throws PublicLinkException {
    if (link.isPasswordSet()) {
      if (Utils.isStringNotNullOrEmpty(passwordHeader)) {
        HashMap<String, String> values = parsePasswordHeader(passwordHeader);

        final String nonce = values.get(Dataplane.nonce);
        final String response = values.get("response");
        final String ha1 = values.get("ha1");
        // let's not share it - just use a constant on each platform
        final String uri = "GET:/files/link";

        if (!Utils.isStringNotNullOrEmpty(nonce)) {
          throw new PublicLinkException(
              "Nonce is required", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
        } else {
          // let's check that the password is encoded correctly
          Item nonceDb = getItem(
              NONCE_TABLE,
              pk,
              Dataplane.nonce.concat(Dataplane.hashSeparator).concat(nonce),
              sk,
              nonce,
              false,
              DataEntityType.CLOUDFIELD);

          // if such nonce exists in the DB - means that it was most likely generated on the FL
          // server
          if (nonceDb != null) {

            // if it's a long nonce (for Xenon) - don't remove it. It'll be removed after TTL (2
            // hours)
            if (!nonce.startsWith("L")) {
              // just remove nonce from the DB to make sure it cannot be reused
              deleteItem(
                  NONCE_TABLE,
                  pk,
                  Dataplane.nonce.concat(Dataplane.hashSeparator).concat(nonce),
                  sk,
                  nonce,
                  DataEntityType.CLOUDFIELD);
            }

            try {
              String ha2 = EncryptHelper.md5Hex(uri);
              String serverResponse = EncryptHelper.md5Hex(ha1 + ":" + nonce + ":" + ha2);
              if (!serverResponse.equals(response)) {
                throw new PublicLinkException(
                    "Digest responses aren't equal", PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
              } else {
                final String encryptedPassword =
                    EncryptHelper.encrypt(ha1, LinkConfiguration.getInstance().getSecretKey());
                if (!encryptedPassword.equals(link.getPassword())) {
                  throw new PublicLinkException(
                      "Password is incorrect", PublicLinkErrorCodes.PASSWORD_IS_INCORRECT);
                }
              }
            } catch (PublicLinkException ple) {
              // just rethrow
              throw ple;
            } catch (Exception encryptionException) {
              throw new PublicLinkException(
                  "Couldn't encrypt the password: " + encryptionException.getMessage(),
                  PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
            }

          } else {
            throw new PublicLinkException(
                "Nonce isn't found in DB", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
          }
        }
      } else {
        throw new PublicLinkException(
            "Password is required", PublicLinkErrorCodes.PASSWORD_IS_REQUIRED);
      }
    }
  }

  public static HashMap<String, String> parsePasswordHeader(String passwordHeader) {
    HashMap<String, String> values = new HashMap<>();
    String[] keyValueArray = passwordHeader.split(",");
    for (String keyVal : keyValueArray) {
      if (keyVal.contains("=")) {
        String key = keyVal.substring(0, keyVal.indexOf("="));
        String value = keyVal.substring(keyVal.indexOf("=") + 1);
        values.put(key.trim(), value.replaceAll("\"", "").trim());
      }
    }
    return values;
  }

  public static void assertLinksEnabledForCompany(Item user) throws PublicLinkException {
    // check if shared links are enabled for the company
    if (user.hasAttribute(Field.ORGANIZATION_ID.getName())
        || LinkConfiguration.getInstance().isEnterprise()) {
      String orgId = LinkConfiguration.getInstance().isEnterprise()
          ? Users.ENTERPRISE_ORG_ID
          : user.getString(Field.ORGANIZATION_ID.getName());
      Item company = Users.getOrganizationById(orgId);
      if (company != null) {
        JsonObject options = company.hasAttribute(Field.OPTIONS.getName())
            ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
            : new JsonObject();
        JsonObject finalOptions =
            LinkConfiguration.getInstance().getCompanyOptions().copy().mergeIn(options, true);
        if (finalOptions.containsKey(Field.SHARED_LINKS.getName())
            && !finalOptions.getBoolean(Field.SHARED_LINKS.getName())) {
          throw new PublicLinkException(
              "Public links aren't allowed", PublicLinkErrorCodes.LINKS_NOT_ALLOWED_BY_COMPANY);
        }
      }
    }
  }

  public static boolean isLinkValid(BaseLink link, String passwordHeader)
      throws PublicLinkException {
    if (!Utils.isStringNotNullOrEmpty(link.getToken())) {
      throw new PublicLinkException(
          "Token isn't specified", PublicLinkErrorCodes.TOKEN_NOT_SPECIFIED);
    }

    boolean isEnabled = link.isEnabled();

    if (!isEnabled) {
      throw new PublicLinkException("Link isn't enabled", PublicLinkErrorCodes.LINK_DISABLED);
    }

    if (link.getExpireDate() > 0 && GMTHelper.utcCurrentTime() > link.getExpireDate()) {
      throw new PublicLinkException("Link has expired", PublicLinkErrorCodes.LINK_EXPIRED);
    }

    LinkService.assertPasswordIsCorrect(
        link, passwordHeader); // if password is incorrect - exception will be thrown

    String userId = link.getUserId();

    if (Objects.isNull(userId)) {
      userId = link.getUpdater();
    }

    if (userId == null) {
      throw new PublicLinkException("User not found", PublicLinkErrorCodes.USER_NOT_FOUND);
    }

    Item user = Users.getUserById(userId);
    if (user == null) {
      return true; // not sure why this is true. We should review
    }
    LinkService.assertLinksEnabledForCompany(user);

    return LinkService.checkLicense(user.getString(Field.USER_EMAIL.getName()));
  }
}
