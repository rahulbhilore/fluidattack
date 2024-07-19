package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class ShareObjectException extends GraphException {
  public <T> ShareObjectException(HttpResponse<T> response) {
    super(ShareObjectException.class.getSimpleName(), response);
  }

  public ShareObjectException(Throwable throwable) {
    super(ShareObjectException.class.getSimpleName(), throwable);
  }
}
