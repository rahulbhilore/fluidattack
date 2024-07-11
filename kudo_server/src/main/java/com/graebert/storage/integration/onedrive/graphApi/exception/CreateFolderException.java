package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class CreateFolderException extends GraphException {
  public <T> CreateFolderException(HttpResponse<T> response) {
    super(CreateFolderException.class.getSimpleName(), response);
  }

  public CreateFolderException(Throwable throwable) {
    super(CreateFolderException.class.getSimpleName(), throwable);
  }
}
