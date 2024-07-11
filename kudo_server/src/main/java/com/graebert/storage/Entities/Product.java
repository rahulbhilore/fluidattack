package com.graebert.storage.Entities;

public enum Product {
  KUDO,
  DRAFTSIGHT;

  public static Product getByName(String name) {
    if (name.toLowerCase().contains("draftsight")) {
      return DRAFTSIGHT;
    }
    return KUDO;
  }
}
