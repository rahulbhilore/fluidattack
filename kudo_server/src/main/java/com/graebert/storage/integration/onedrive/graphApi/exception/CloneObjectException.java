package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class CloneObjectException extends GraphException {
  public <T> CloneObjectException(HttpResponse<T> response) {
    super(CloneObjectException.class.getSimpleName(), response);
  }

  public CloneObjectException(Throwable throwable) {
    super(CloneObjectException.class.getSimpleName(), throwable);
  }
}
