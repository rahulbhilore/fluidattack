package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetSitesException extends GraphException {
  public <T> GetSitesException(HttpResponse<T> response) {
    super(GetSitesException.class.getSimpleName(), response);
  }

  public GetSitesException(Throwable throwable) {
    super(GetSitesException.class.getSimpleName(), throwable);
  }
}
