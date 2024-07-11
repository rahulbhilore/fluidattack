package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetDriveInfoException extends GraphException {
  public <T> GetDriveInfoException(HttpResponse<T> response) {
    super(GetDriveInfoException.class.getSimpleName(), response);
  }

  public GetDriveInfoException(Throwable throwable) {
    super(GetDriveInfoException.class.getSimpleName(), throwable);
  }
}
