package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetMyDriveException extends GraphException {
  public <T> GetMyDriveException(HttpResponse<T> response) {
    super(GetMyDriveException.class.getSimpleName(), response);
  }

  public GetMyDriveException(Throwable throwable) {
    super(GetMyDriveException.class.getSimpleName(), throwable);
  }
}
