package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class CheckFileException extends GraphException {
  public <T> CheckFileException(HttpResponse<T> response) {
    super(CheckFileException.class.getSimpleName(), response);
  }

  public CheckFileException(Throwable throwable) {
    super(CheckFileException.class.getSimpleName(), throwable);
  }
}
