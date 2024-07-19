package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetObjectInfoException extends GraphException {
  public <T> GetObjectInfoException(HttpResponse<T> response) {
    super(GetObjectInfoException.class.getSimpleName(), response);
  }

  public GetObjectInfoException(Throwable throwable) {
    super(GetObjectInfoException.class.getSimpleName(), throwable);
  }
}
