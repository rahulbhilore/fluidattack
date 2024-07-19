package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetRootInfoException extends GraphException {
  public <T> GetRootInfoException(HttpResponse<T> response) {
    super(GetRootInfoException.class.getSimpleName(), response);
  }

  public GetRootInfoException(Throwable throwable) {
    super(GetRootInfoException.class.getSimpleName(), throwable);
  }
}
