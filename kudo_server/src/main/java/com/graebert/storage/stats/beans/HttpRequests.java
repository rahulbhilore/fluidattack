package com.graebert.storage.stats.beans;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HttpRequests implements HttpRequestsMBean {
  // configuration properties for easier updates
  private static final TimeUnit period = TimeUnit.MINUTES;
  private static final int[] sampleRanges = {1, 2, 5, 10};

  // helpers
  private static final List<Integer> framesList =
      Arrays.stream(sampleRanges).boxed().collect(Collectors.toList());
  private static final int maxTime = Collections.max(framesList);
  private static final int minTime = Collections.min(framesList);
  // timer period. IMO, should be minTime/2
  private static final long timerPeriod = period.toMillis(minTime) / 2;

  // AP: should these also be ConcurrentHashMap?
  // Didn't see such logs, not sure if it used that much,
  // but be aware of possible ConcurrentModificationException's
  private final Map<Integer, Long> minTimes;
  private final Map<Integer, Long> maxTimes;
  private final Map<Integer, Float> avgTimes;
  private final Map<Integer, Integer> responses1xx;
  private final Map<Integer, Integer> responses2xx;
  private final Map<Integer, Integer> responses3xx;
  private final Map<Integer, Integer> responses4xx;
  private final Map<Integer, Integer> responses5xx;
  private final ConcurrentMap<Integer, Integer> maxConcurrentConnections;
  // this should store time frames per requests
  // range -> start, end
  private CopyOnWriteArrayList<Long> requestsFrames;
  private CopyOnWriteArrayList<HttpRequest> requests;
  private Integer openConnections;
  private Integer totalAmountOfConnections;
  private long firstRequestTime;

  public HttpRequests() {
    this.minTimes = new HashMap<>();
    this.avgTimes = new HashMap<>();
    this.maxTimes = new HashMap<>();
    this.responses5xx = new HashMap<>();
    this.responses4xx = new HashMap<>();
    this.responses3xx = new HashMap<>();
    this.responses2xx = new HashMap<>();
    this.responses1xx = new HashMap<>();
    this.maxConcurrentConnections =
        framesList.stream().collect(Collectors.toConcurrentMap(key -> key, value -> 0));
    this.requestsFrames = new CopyOnWriteArrayList<>();
    this.openConnections = 0;
    this.totalAmountOfConnections = 0;
    this.requests = new CopyOnWriteArrayList<>();
    this.firstRequestTime = Long.MAX_VALUE;
    Timer timer = new Timer();
    timer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            // reset data
            updateMaxConcurrency();
            checkAndShrinkRequests();
            recalculateData();
          }
        },
        0,
        timerPeriod);
  }

  public void openedConnection() {
    this.openConnections += 1;
    this.totalAmountOfConnections += 1;
    this.maxConcurrentConnections.forEach((time, amount) -> {
      if (this.openConnections > amount) {
        this.maxConcurrentConnections.put(time, amount);
      }
    });
  }

  private void updateMaxConcurrency() {
    final long millis = System.currentTimeMillis();
    this.maxConcurrentConnections.forEach((time, amount) -> {
      if (this.requestsFrames.isEmpty()) {
        this.maxConcurrentConnections.put(time, 0);
      } else {
        final long timeout = period.toMillis(time);
        final long startTime = millis - timeout;
        // get requests that was executing in that timeframe
        int counter = 0;
        int maxCounted = 0;
        List<Long> sortedRequests = new ArrayList<>(this.requestsFrames);
        sortedRequests = sortedRequests.stream()
            .filter(requestTime ->
                Math.abs(requestTime) >= startTime || Math.abs(requestTime) <= millis)
            .sorted(Comparator.comparingLong(Math::abs))
            .collect(Collectors.toList());
        for (long frame : sortedRequests) {
          counter += frame > 0 ? 1 : -1;
          if (counter > maxCounted) {
            maxCounted = counter;
          }
        }
        this.maxConcurrentConnections.put(time, maxCounted);
      }
    });
  }

  private CopyOnWriteArrayList<HttpRequest> getSubList(int duration) {
    final long millis = System.currentTimeMillis();
    final long timeout = period.toMillis(duration);

    return this.requests.stream()
        .filter(request -> Math.abs(millis - request.getStartTime()) <= timeout)
        .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
  }

  private void checkAndShrinkRequests() {
    // to prevent double executions
    if (firstRequestTime < Long.MAX_VALUE) {
      final long millis = System.currentTimeMillis();
      final long timeout = period.toMillis(maxTime * 2L);
      if (firstRequestTime < (millis - timeout)) {
        requests = getSubList(maxTime);
        // we don't really care about this value - we'll set new date next time
        firstRequestTime = Long.MAX_VALUE;
        // filter frames to include only those in timeframe [maxTime;currentTime]
        requestsFrames = requestsFrames.stream()
            .filter(requestTime -> (millis - Math.abs(requestTime)) <= maxTime)
            .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
      }
    }
  }

  private void recalculateData() {
    // take for last 5/10/20 minutes and recalculate
    // DK: we could store sum of times per last X for example
    // and make -Xth request + new one -> recalculate
    // but I think given how small HttpRequest class is
    // there might not be any performance difference

    framesList.forEach(amount -> {
      CopyOnWriteArrayList<HttpRequest> list = this.getSubList(amount);

      long min = 0;
      float avg = 0;
      long max = 0;
      int responses1xx = 0;
      int responses2xx = 0;
      int responses3xx = 0;
      int responses4xx = 0;
      int responses5xx = 0;
      int iterated = 0;
      long timeSum = 0;

      // iterate each list, calculate data and set
      for (HttpRequest request1 : list) {
        long executionTime = request1.getExecutionTime();
        int status = request1.getStatus();
        switch (status / 100) {
          case 1:
            responses1xx += 1;
            break;
          case 2:
            responses2xx += 1;
            break;
          case 3:
            responses3xx += 1;
            break;
          case 4:
            responses4xx += 1;
            break;
          case 5:
            responses5xx += 1;
            break;
          default:
            break;
        }
        if (executionTime < min || min == 0) {
          min = executionTime;
        }
        if (executionTime > max || max == 0) {
          max = executionTime;
        }
        iterated += 1;
        timeSum += executionTime;
        avg = ((float) timeSum / iterated);
      }

      this.minTimes.put(amount, min);
      this.avgTimes.put(amount, avg);
      this.maxTimes.put(amount, max);
      this.responses1xx.put(amount, responses1xx);
      this.responses2xx.put(amount, responses2xx);
      this.responses3xx.put(amount, responses3xx);
      this.responses4xx.put(amount, responses4xx);
      this.responses5xx.put(amount, responses5xx);
    });
  }

  public void closedConnection(HttpRequest request) {
    this.requests.add(request);
    this.requestsFrames.add(request.getStartTime());
    this.requestsFrames.add(-request.getEndTime());
    if (request.getStartTime() < this.firstRequestTime) {
      this.firstRequestTime = request.getStartTime();
    }
    this.openConnections -= 1;
    // we don't need to store all values - let's get sublist if we have too much
    checkAndShrinkRequests();
    this.recalculateData();
  }

  @Override
  public Map<Integer, Long> getMinTime() {
    return minTimes;
  }

  @Override
  public Map<Integer, Long> getMaxTime() {
    return maxTimes;
  }

  @Override
  public Map<Integer, Float> getAvgTime() {
    return avgTimes;
  }

  @Override
  public Map<Integer, Integer> getResponses1xx() {
    return this.responses1xx;
  }

  @Override
  public Map<Integer, Integer> getResponses2xx() {
    return this.responses2xx;
  }

  @Override
  public Map<Integer, Integer> getResponses3xx() {
    return this.responses3xx;
  }

  @Override
  public Map<Integer, Integer> getResponses4xx() {
    return this.responses4xx;
  }

  @Override
  public Map<Integer, Integer> getResponses5xx() {
    return this.responses5xx;
  }

  @Override
  public Integer getCurrentAmountOfConnections() {
    return openConnections;
  }

  @Override
  public Integer getTotalAmountOfConnections() {
    return totalAmountOfConnections;
  }

  @Override
  public Map<Integer, Integer> getMaxConcurrentConnection() {
    return this.maxConcurrentConnections;
  }
}
