package com.graebert.storage.security;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.jetbrains.annotations.NonNls;

public class EncryptHelper {
  private static Cipher asymmetricCiper;

  static {
    try {
      asymmetricCiper = Cipher.getInstance("RSA");
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static String rsaDecode(String str, String keyString) throws Exception {
    if (str == null) {
      return null;
    }
    KeyFactory kf = KeyFactory.getInstance("RSA");
    //        InputStream stream = StorageVerticle.class.getResourceAsStream("/KeyPair/publicKey");
    //        byte[] keyBytes = IOUtils.toByteArray(stream);
    byte[] keyBytes = java.util.Base64.getDecoder().decode(keyString);
    X509EncodedKeySpec spec1 = new X509EncodedKeySpec(keyBytes);
    Key publicKey = kf.generatePublic(spec1);

    asymmetricCiper.init(Cipher.DECRYPT_MODE, publicKey);
    return new String(asymmetricCiper.doFinal(Base64.decodeBase64(str)), StandardCharsets.UTF_8);
  }

  public static String md5Hex(@NonNls String secret) {
    return DigestUtils.md5Hex(secret);
  }

  public static String encodePassword(String secret) throws IOException, NoSuchAlgorithmException {
    DigestInputStream inputStream = new DigestInputStream(
        new ByteArrayInputStream(secret.getBytes()), MessageDigest.getInstance("MD5"));
    int count;
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    while ((count = inputStream.read()) != -1) {
      outputStream.write(count);
    }

    String result = new String(
            Base64.encodeBase64(inputStream.getMessageDigest().digest()), StandardCharsets.UTF_8)
        .trim();
    outputStream.close();
    inputStream.close();
    return result;
  }

  public static String encrypt(String str, String key)
      throws UnsupportedEncodingException, BadPaddingException, IllegalBlockSizeException,
          NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
    if (str == null || key == null) {
      return null;
    }
    SecretKey secretKey = new MySecretKey(key);
    Cipher ecipher = Cipher.getInstance("DES");
    ecipher.init(Cipher.ENCRYPT_MODE, secretKey);
    byte[] utf8 = str.getBytes(StandardCharsets.UTF_8);
    byte[] enc = ecipher.doFinal(utf8);
    String base64 = new String(Base64.encodeBase64(enc), StandardCharsets.UTF_8);
    return URLEncoder.encode(base64, StandardCharsets.UTF_8);
  }

  public static String decrypt(String str, String key)
      throws UnsupportedEncodingException, BadPaddingException, IllegalBlockSizeException,
          NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
    if (str == null || key == null) {
      return null;
    }
    SecretKey secretKey = new MySecretKey(key);
    Cipher dcipher = Cipher.getInstance("DES");
    dcipher.init(Cipher.DECRYPT_MODE, secretKey);
    String base64 = URLDecoder.decode(str, StandardCharsets.UTF_8);
    byte[] dec = Base64.decodeBase64(base64.getBytes(StandardCharsets.UTF_8));
    byte[] utf8 = dcipher.doFinal(dec);
    return new String(utf8, StandardCharsets.UTF_8);
  }

  public static String intercomHash(String userid, String intercomAppId)
      throws NoSuchAlgorithmException, InvalidKeyException {
    String secret;
    if (intercomAppId != null) {
      switch (intercomAppId) {
        case "th439yfd": // india
          secret = "GzH0x0u4Hys5EHiDppoOo19zZ62CEcgESuFVlkh3";
        case "ocbnliu5": // japan
          secret = "FnpijXQlWDddCzv4y7H48VPdip_hX16LTQZpjZsA";
          break;
        case "r0oqgugm": // gmbh test
          secret = "ZK4JH3mJ3gkfpORk64P8Lb5H8QMcQTOPrcFa6qiv";
          break;
        case "qi3oymi1": // india test
          secret = "7EZAHojZp0jG5lGfsaxd_VJ8n0p5kcn3_msqOOQk";
          break;
        case "t6ykzz03": // japan test
          secret = "sf68qgrG9vhhMckcmxgM2Lh3RChejz7Y69RWB_VO";
          break;
        case "dzoczj6l": // gmbh
        default:
          secret = "HV9QzI3QNw91euz467NfDojnOtAl0Ah-qj4XT4s3";
      }
    } else {
      secret = "HV9QzI3QNw91euz467NfDojnOtAl0Ah-qj4XT4s3";
    }
    Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
    SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
    sha256_HMAC.init(secret_key);
    return Hex.encodeHexString(sha256_HMAC.doFinal(userid.getBytes()));
  }

  public static Key createRSA(JsonObject json, boolean returnPublic)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    Map<String, String> alias = new HashMap<>() {
      {
        this.put("RS256", "SHA256withRSA");
        this.put("RS384", "SHA384withRSA");
        this.put("RS512", "SHA512withRSA");
      }
    };
    String alg = json.getString("alg", "RS256");
    if (!alias.containsKey(alg)) {
      throw new NoSuchAlgorithmException(alg);
    } else {
      BigInteger n;
      BigInteger e;
      if (returnPublic && jsonHasProperties(json, "n", "e")) {
        n = new BigInteger(1, java.util.Base64.getUrlDecoder().decode(json.getString("n")));
        e = new BigInteger(1, java.util.Base64.getUrlDecoder().decode(json.getString("e")));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
      }

      if (!returnPublic
          && jsonHasProperties(json, "n", "e" /*, "d", "p", "q", "dp", "dq", "qi"*/)) {
        n = new BigInteger(1, java.util.Base64.getUrlDecoder().decode(json.getString("n")));
        e = new BigInteger(1, java.util.Base64.getUrlDecoder().decode(json.getString("e")));
        //                BigInteger d = new BigInteger(1,
        // java.util.Base64.getUrlDecoder().decode(json.getString("d")));
        //                BigInteger p = new BigInteger(1,
        // java.util.Base64.getUrlDecoder().decode(json.getString("p")));
        //                BigInteger q = new BigInteger(1,
        // java.util.Base64.getUrlDecoder().decode(json.getString("q")));
        //                BigInteger dp = new BigInteger(1,
        // java.util.Base64.getUrlDecoder().decode(json.getString("dp")));
        //                BigInteger dq = new BigInteger(1,
        // java.util.Base64.getUrlDecoder().decode(json.getString("dq")));
        //                BigInteger qi = new BigInteger(1,
        // java.util.Base64.getUrlDecoder().decode(json.getString("qi")));
        System.out.println("JWK $$$ " + json.encodePrettily());
        System.out.println("n $$$ " + n);
        System.out.println("e $$$ " + e);
        PrivateKey key = KeyFactory.getInstance("RSA")
            .generatePrivate(new RSAPrivateCrtKeySpec(
                n, e, null, null, null, null, null, null)); // d, p, q, dp, dq, qi));
        System.out.println(key == null);
        return key;
      }

      if (json.containsKey("x5c")) {
        JsonArray x5c = json.getJsonArray("x5c");
        if (x5c.size() > 1) {
          throw new RuntimeException("Certificate Chain length > 1 is not supported");
        }

        //                CertificateFactory cf = CertificateFactory.getInstance("X.509");
        //                certificate = (X509Certificate)cf.generateCertificate(new
        // ByteArrayInputStream(x5c.getString(0).getBytes(UTF8)));
      }

      String var13 = json.getString("use", "sig");
      byte var15 = -1;
      switch (var13.hashCode()) {
        case 100570:
          if (var13.equals("enc")) {
            var15 = 1;
          }
          break;
        case 113873:
          if (var13.equals("sig")) {
            var15 = 0;
          }
      }

      switch (var15) {
        case 0:
          //                    try {
          //                        signature = Signature.getInstance((String)alias.get(this.alg));
          //                        break;
          //                    } catch (NoSuchAlgorithmException var11) {
          //                        throw new RuntimeException(var11);
          //                    }
        case 1:
          //                    cipher = Cipher.getInstance("RSA");
      }
      return null;
    }
  }

  public static boolean jsonHasProperties(JsonObject json, String... properties) {
    return Arrays.stream(properties).allMatch(json::containsKey);
  }

  public static SecretKey generateRandomKey() throws NoSuchAlgorithmException {
    KeyGenerator keygen = KeyGenerator.getInstance("DES");

    SecureRandom secRandom = new SecureRandom();
    keygen.init(secRandom);
    return keygen.generateKey();
  }

  private static final class MySecretKey implements SecretKey {
    // key shouldn't be longer than 8 byte; moved to secrets manager as encrypted base64 string
    // (Base64.getEncoder().encodeToString(bytes);)
    private final byte[] key;

    MySecretKey(String key) {
      this.key = java.util.Base64.getDecoder().decode(key);
    }

    @NonNls
    public String getAlgorithm() {
      return "DES";
    }

    @NonNls
    public String getFormat() {
      return "RAW";
    }

    public byte[] getEncoded() {
      return key;
    }
  }
}
