package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpResponse;

public class RemoveObjectPermissionException extends GraphException {
  public <T> RemoveObjectPermissionException(HttpResponse<T> response) {
    super(RemoveObjectPermissionException.class.getSimpleName(), response);
  }

  public RemoveObjectPermissionException(Throwable throwable) {
    super(RemoveObjectPermissionException.class.getSimpleName(), throwable);
  }
}
