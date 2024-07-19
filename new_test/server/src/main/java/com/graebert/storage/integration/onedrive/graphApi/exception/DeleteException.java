package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class DeleteException extends GraphException {
  public <T> DeleteException(HttpResponse<T> response) {
    super(DeleteException.class.getSimpleName(), response);
  }

  public DeleteException(Throwable throwable) {
    super(DeleteException.class.getSimpleName(), throwable);
  }
}
