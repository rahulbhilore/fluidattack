package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetFolderContentException extends GraphException {
  public <T> GetFolderContentException(HttpResponse<T> response) {
    super(GetFolderContentException.class.getSimpleName(), response);
  }

  public GetFolderContentException(Throwable throwable) {
    super(GetFolderContentException.class.getSimpleName(), throwable);
  }
}
