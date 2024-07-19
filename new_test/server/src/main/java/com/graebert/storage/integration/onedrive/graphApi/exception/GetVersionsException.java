package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetVersionsException extends GraphException {
  public <T> GetVersionsException(HttpResponse<T> response) {
    super(GetVersionsException.class.getSimpleName(), response);
  }

  public GetVersionsException(Throwable throwable) {
    super(GetVersionsException.class.getSimpleName(), throwable);
  }
}
