package com.graebert.storage.integration.onedrive.graphApi.exception;

import kong.unirest.HttpStatus;

public class UnauthorizedException extends GraphException {
  public UnauthorizedException(Throwable throwable) {
    super(UnauthorizedException.class.getSimpleName(), throwable, HttpStatus.BAD_REQUEST);
  }
}
