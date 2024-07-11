package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class UploadVersionException extends GraphException {
  public <T> UploadVersionException(HttpResponse<T> response) {
    super(UploadVersionException.class.getSimpleName(), response);
  }

  public UploadVersionException(Throwable throwable) {
    super(UploadVersionException.class.getSimpleName(), throwable);
  }
}
