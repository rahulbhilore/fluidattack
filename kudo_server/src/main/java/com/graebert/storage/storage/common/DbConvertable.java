package com.graebert.storage.storage.common;

import com.amazonaws.services.dynamodbv2.document.Item;

/**
 * Interface applies contract to objects that may be used with Dynamo Repositories
 */
public interface DbConvertable {
  Item toItem();
}
