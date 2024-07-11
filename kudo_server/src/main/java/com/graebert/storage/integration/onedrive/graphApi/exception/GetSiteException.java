package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetSiteException extends GraphException {
  public <T> GetSiteException(HttpResponse<T> response) {
    super(GetSiteException.class.getSimpleName(), response);
  }

  public GetSiteException(Throwable throwable) {
    super(GetSiteException.class.getSimpleName(), throwable);
  }
}
