package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class WrongNameException extends GraphException {
  public <T> WrongNameException(HttpResponse<T> response) {
    super(WrongNameException.class.getSimpleName(), response);
  }

  public WrongNameException() {
    super(WrongNameException.class.getSimpleName(), new Exception("wrongName"));
  }

  public WrongNameException(Throwable throwable) {
    super(WrongNameException.class.getSimpleName(), throwable);
  }
}
