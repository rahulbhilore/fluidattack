package com.graebert.storage.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class TypedIteratorUtils {
  public static <T> List<T> toTypedList(Iterator<T> iterator) {
    return toTypedList(iterator, 10);
  }

  public static <T> List<T> toTypedList(Iterator<T> iterator, int estimatedSize) {
    if (iterator == null) {
      throw new NullPointerException("Iterator must not be null");
    }
    if (estimatedSize < 1) {
      throw new IllegalArgumentException("Estimated size must be greater than 0");
    }

    List<T> list = new ArrayList<>(estimatedSize);

    while (iterator.hasNext()) {
      list.add(iterator.next());
    }

    return list;
  }
}
