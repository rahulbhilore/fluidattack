package com.graebert.storage.util.message;

import java.io.IOException;

public class ParsedMessageException extends IOException {
  public ParsedMessageException(String message) {
    super(message);
  }
}
