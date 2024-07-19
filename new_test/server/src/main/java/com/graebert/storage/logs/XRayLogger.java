package com.graebert.storage.logs;

import com.amazonaws.xray.listeners.SegmentListener;

public class XRayLogger extends BaseLogger implements SegmentListener {
  @Override
  protected LogGroup getLogGroup() {
    return LogGroup.XRAY;
  }

  @Override
  protected String getAnsiPrefix() {
    return AnsiColors.ANSI_CYAN.getColor();
  }

  //  @Override
  //  public void onBeginSegment(Segment segment) {
  //    logToConsole("[Stack Segment]: " + getReadableStackTrace());
  //
  //    new XRayLogger().log(Level.INFO, "onBeginSegment", String.format(
  //      "name: %s, traceId: %s, segmentId: %s, fullSegmentJson: %s",
  //      segment.getName(),
  //      segment.getTraceId(),
  //      segment.getId(),
  //      convertEntityToJson(segment).toString()
  //    ));
  //  }

  //  @Override
  //  public void beforeEndSegment(Segment segment) {
  //    new XRayLogger().log(Level.INFO, "beforeEndSegment", String.format(
  //      "name: %s, traceId: %s, segmentId: %s, fullSegmentJson: %s",
  //      segment.getName(),
  //      segment.getTraceId(),
  //      segment.getId(),
  //      convertEntityToJson(segment).toString()
  //    ));
  //  }
  //
  //  @Override
  //  public void afterEndSegment(Segment segment) {
  //    new XRayLogger().log(Level.INFO, "afterEndSegment", String.format(
  //      "name: %s, traceId: %s, segmentId: %s, fullSegmentJson: %s",
  //      segment.getName(),
  //      segment.getTraceId(),
  //      segment.getId(),
  //      convertEntityToJson(segment).toString()
  //    ));
  //  }
  //
  //  @Override
  //  public void onBeginSubsegment(Subsegment subsegment) {
  //    new XRayLogger().log(Level.INFO, "onBeginSubsegment", String.format(
  //      "name: %s, traceId: %s, parentSegmentId: %s, subsegmentId: %s, fullSubsegmentJson: %s",
  //      subsegment.getName(),
  //      subsegment.getTraceId(),
  //      subsegment.getParentSegment().getId(),
  //      subsegment.getId(),
  //      convertEntityToJson(subsegment).toString()
  //    ));
  //  }
  //
  //  @Override
  //  public void beforeEndSubsegment(Subsegment subsegment) {
  //    new XRayLogger().log(Level.INFO, "beforeEndSubsegment", String.format(
  //      "name: %s, traceId: %s, parentSegmentId: %s, subsegmentId: %s, fullSubsegmentJson: %s",
  //      subsegment.getName(),
  //      subsegment.getTraceId(),
  //      subsegment.getParentSegment().getId(),
  //      subsegment.getId(),
  //      convertEntityToJson(subsegment).toString()
  //    ));
  //  }
  //
  //  @Override
  //  public void afterEndSubsegment(Subsegment subsegment) {
  //    new XRayLogger().log(Level.INFO, "afterEndSubsegment", String.format(
  //      "name: %s, traceId: %s, parentSegmentId: %s, subsegmentId: %s, fullSubsegmentJson: %s",
  //      subsegment.getName(),
  //      subsegment.getTraceId(),
  //      subsegment.getParentSegment().getId(),
  //      subsegment.getId(),
  //      convertEntityToJson(subsegment).toString()
  //    ));
  //  }
}
