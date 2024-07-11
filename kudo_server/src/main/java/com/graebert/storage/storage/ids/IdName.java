package com.graebert.storage.storage.ids;

import com.graebert.storage.util.Field;

public enum IdName {
  FILE(Field.FILE_ID.getName()),
  FOLDER(Field.FOLDER_ID.getName()),
  OBJECT(Field.OBJECT_ID.getName()),
  ID(Field.ID.getName());

  private final String name;

  IdName(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
