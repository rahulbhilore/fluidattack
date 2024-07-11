package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetSiteDrivesException extends GraphException {
  public <T> GetSiteDrivesException(HttpResponse<T> response) {
    super(GetSiteDrivesException.class.getSimpleName(), response);
  }

  public GetSiteDrivesException(Throwable throwable) {
    super(GetSiteDrivesException.class.getSimpleName(), throwable);
  }
}
