package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class MoveException extends GraphException {
  public <T> MoveException(HttpResponse<T> response) {
    super(MoveException.class.getSimpleName(), response);
  }

  public MoveException(Throwable throwable) {
    super(MoveException.class.getSimpleName(), throwable);
  }
}
