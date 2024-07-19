package com.graebert.storage.integration;

import com.onelogin.saml2.authn.SamlResponse;
import com.onelogin.saml2.settings.Saml2Settings;
import com.onelogin.saml2.settings.SettingsBuilder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Saml {
  public static Logger log = LogManager.getRootLogger();

  public static SamlResponse parse(String samlResponse) {
    try {
      samlResponse = URLDecoder.decode(samlResponse, StandardCharsets.UTF_8);
      KeyStore keystore = KeyStore.getInstance("PKCS12");
      keystore.load(Saml.class.getResourceAsStream("/keystore.p12"), "felix123".toCharArray());
      PrivateKey key = (PrivateKey) keystore.getKey("rsassokey", "felix123".toCharArray());

      Map<String, Object> samlData = new HashMap<>();
      samlData.put(SettingsBuilder.SP_PRIVATEKEY_PROPERTY_KEY, key);

      Saml2Settings settings = new SettingsBuilder().fromValues(samlData).build();
      SamlResponse response = new SamlResponse(settings, null);
      response.loadXmlFromBase64(samlResponse);
      return response;
    } catch (Exception e) {
      log.error("Exception on parsing SAMLResponse: ", e);
      e.printStackTrace();
      return null;
    }
  }
}
