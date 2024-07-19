package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetObjectPermissionsException extends GraphException {
  public <T> GetObjectPermissionsException(HttpResponse<T> response) {
    super(GetObjectPermissionsException.class.getSimpleName(), response);
  }

  public GetObjectPermissionsException(Throwable throwable) {
    super(GetObjectPermissionsException.class.getSimpleName(), throwable);
  }
}
