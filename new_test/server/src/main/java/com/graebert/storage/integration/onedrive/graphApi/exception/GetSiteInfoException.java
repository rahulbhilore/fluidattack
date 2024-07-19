package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class GetSiteInfoException extends GraphException {
  public <T> GetSiteInfoException(HttpResponse<T> response) {
    super(GetSiteInfoException.class.getSimpleName(), response);
  }

  public GetSiteInfoException(Throwable throwable) {
    super(GetSiteInfoException.class.getSimpleName(), throwable);
  }
}
