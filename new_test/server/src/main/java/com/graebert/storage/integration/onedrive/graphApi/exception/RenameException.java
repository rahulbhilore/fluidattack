package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class RenameException extends GraphException {
  public <T> RenameException(HttpResponse<T> response) {
    super(RenameException.class.getSimpleName(), response);
  }

  public RenameException(Throwable throwable) {
    super(RenameException.class.getSimpleName(), throwable);
  }
}
