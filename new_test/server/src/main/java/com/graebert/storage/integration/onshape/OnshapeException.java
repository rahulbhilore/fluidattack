package com.graebert.storage.integration.onshape;

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;

public class OnshapeException extends Exception {

  private HttpResponse<JsonNode> response;

  public OnshapeException(String errorMessage) {
    super(errorMessage);
  }

  public OnshapeException(String errorMessage, Throwable rootCause) {
    super(errorMessage, rootCause);
  }

  public OnshapeException(String errorMessage, HttpResponse<JsonNode> originalResponse) {
    super(errorMessage);
    this.response = originalResponse;
  }

  public OnshapeException(
      String errorMessage, Throwable rootCause, HttpResponse<JsonNode> originalResponse) {
    super(errorMessage, rootCause);
    this.response = originalResponse;
  }

  public HttpResponse<JsonNode> getResponse() {
    return this.response;
  }
}
