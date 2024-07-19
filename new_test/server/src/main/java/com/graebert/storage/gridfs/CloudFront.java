package com.graebert.storage.gridfs;

import com.amazonaws.services.cloudfront.CloudFrontUrlSigner;
import java.security.PrivateKey;
import java.util.Date;

public class CloudFront {

  public static String generateSignedUrl(
      PrivateKey cloudFrontPrivateKey, String cidrRange, String cloudFrontKeyPairId) {
    String resourcePath = "foo/bar.html";
    Date expirationDate = new Date(System.currentTimeMillis() + 60 * 1000);
    String policy = CloudFrontUrlSigner.buildCustomPolicyForSignedUrl(
        resourcePath, expirationDate, cidrRange, null);
    return CloudFrontUrlSigner.getSignedURLWithCustomPolicy(
        resourcePath, cloudFrontKeyPairId, cloudFrontPrivateKey, policy);
  }

  /*public static String generatedUrl(PrivateKey cloudFrontPrivateKey, String cidrRange, String cloudFrontKeyPairId, boolean encode, long expiration) {
      URL url = CloudFront.
      if (encode) {
          try {
              return url.toString().replace(url.getPath(), URLDecoder.decode(url.getPath(), "UTF-8"));
          } catch (UnsupportedEncodingException e) {
              log.error("Error on decoding presigned url", e);
              return "";
          }
      } else
          return url.toString();
  }*/

}
