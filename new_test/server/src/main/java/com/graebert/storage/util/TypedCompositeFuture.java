package com.graebert.storage.util;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.impl.future.CompositeFutureImpl;
import java.util.List;

public interface TypedCompositeFuture extends CompositeFuture {
  static <T> CompositeFuture join(List<Future<T>> futures) {
    return CompositeFutureImpl.join(futures.toArray(new Future[0]));
  }

  static <T> CompositeFuture all(List<Future<T>> futures) {
    return CompositeFutureImpl.all(futures.toArray(new Future[0]));
  }
}
