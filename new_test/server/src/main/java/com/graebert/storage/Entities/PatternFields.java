package com.graebert.storage.Entities;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class PatternFields {
  public String email;
  public String firstName;
  public String lastName;

  public PatternFields(final String email, final String firstName, final String lastName) {
    this.email = email;
    this.firstName = firstName;
    this.lastName = lastName;
  }

  public boolean isSpacedPatternMatched(String subFilter) {
    Pattern subPattern = Pattern.compile(Pattern.quote(subFilter), Pattern.CASE_INSENSITIVE);
    for (Field field : PatternFields.class.getFields()) {
      String fieldValue;
      try {
        fieldValue = (String) field.get(this);
        if (subPattern.matcher(fieldValue).find()) {
          return true;
        }
      } catch (IllegalAccessException | NullPointerException ignore) {
      }
    }
    return false;
  }
}
