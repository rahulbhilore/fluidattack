package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class RestoreObjectException extends GraphException {
  public <T> RestoreObjectException(HttpResponse<T> response) {
    super(RestoreObjectException.class.getSimpleName(), response);
  }

  public RestoreObjectException(Throwable throwable) {
    super(RestoreObjectException.class.getSimpleName(), throwable);
  }
}
