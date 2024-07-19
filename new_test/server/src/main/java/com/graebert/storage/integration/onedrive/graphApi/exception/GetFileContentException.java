package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetFileContentException extends GraphException {
  public <T> GetFileContentException(HttpResponse<T> response) {
    super(GetFileContentException.class.getSimpleName(), response);
  }

  public GetFileContentException(Throwable throwable) {
    super(GetFileContentException.class.getSimpleName(), throwable);
  }
}
