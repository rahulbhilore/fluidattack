package com.graebert.storage.stats.beans;

import java.util.Map;

public interface HttpRequestsMBean {
  Map<Integer, Long> getMinTime();

  Map<Integer, Long> getMaxTime();

  Map<Integer, Float> getAvgTime();

  Map<Integer, Integer> getResponses1xx();

  Map<Integer, Integer> getResponses2xx();

  Map<Integer, Integer> getResponses3xx();

  Map<Integer, Integer> getResponses4xx();

  Map<Integer, Integer> getResponses5xx();

  Integer getCurrentAmountOfConnections();

  Integer getTotalAmountOfConnections();

  Map<Integer, Integer> getMaxConcurrentConnection();
}
