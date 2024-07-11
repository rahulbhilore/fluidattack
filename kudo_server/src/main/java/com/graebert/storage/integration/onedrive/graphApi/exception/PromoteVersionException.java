package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class PromoteVersionException extends GraphException {
  public <T> PromoteVersionException(HttpResponse<T> response) {
    super(PromoteVersionException.class.getSimpleName(), response);
  }

  public PromoteVersionException(Throwable throwable) {
    super(PromoteVersionException.class.getSimpleName(), throwable);
  }
}
