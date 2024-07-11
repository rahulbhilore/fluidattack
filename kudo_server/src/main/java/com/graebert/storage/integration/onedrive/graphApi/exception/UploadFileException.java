package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class UploadFileException extends GraphException {
  public <T> UploadFileException(HttpResponse<T> response) {
    super(UploadFileException.class.getSimpleName(), response);
  }

  public UploadFileException(Throwable throwable) {
    super(UploadFileException.class.getSimpleName(), throwable);
  }
}
