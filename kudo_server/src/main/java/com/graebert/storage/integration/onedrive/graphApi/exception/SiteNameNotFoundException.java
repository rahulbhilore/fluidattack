package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class SiteNameNotFoundException extends GraphException {
  public <T> SiteNameNotFoundException(HttpResponse<T> response) {
    super(SiteNameNotFoundException.class.getSimpleName(), response);
  }

  public SiteNameNotFoundException(Throwable throwable) {
    super(SiteNameNotFoundException.class.getSimpleName(), throwable);
  }

  public SiteNameNotFoundException() {
    super(SiteNameNotFoundException.class.getSimpleName(), new Exception("bad income event"));
  }
}
