package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Subsegment;
import com.graebert.storage.util.Field;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import java.io.IOException;
import java.util.concurrent.Future;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;

public class AWSXRayTracedHttpAsyncClientBuilder extends HttpAsyncClientBuilder {
  public static AWSXRayTracedHttpAsyncClientBuilder create() {
    return new AWSXRayTracedHttpAsyncClientBuilder();
  }

  @Override
  public CloseableHttpAsyncClient build() {
    // super.addInterceptorFirst(new HandleSend());
    return new TracingHttpAsyncClient(super.build());
  }

  final class TracingHttpAsyncClient extends CloseableHttpAsyncClient {
    private final CloseableHttpAsyncClient delegate;

    TracingHttpAsyncClient(CloseableHttpAsyncClient delegate) {
      this.delegate = delegate;
      this.delegate.start();
    }

    @Override
    public <T> Future<T> execute(
        HttpAsyncRequestProducer requestProducer,
        HttpAsyncResponseConsumer<T> responseConsumer,
        HttpContext context,
        FutureCallback<T> callback) {
      Subsegment subsegment = (Subsegment) XRayManager.createSubSegment(
          OperationGroup.UNNAMED, requestProducer.getTarget().getHostName());
      return delegate.execute(
          new TracingAsyncRequestProducer(requestProducer, context, subsegment),
          new TracingAsyncResponseConsumer<>(responseConsumer, context, subsegment),
          context,
          callback);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public boolean isRunning() {
      return delegate.isRunning();
    }

    @Override
    public void start() {
      delegate.start();
    }
  }

  final class TracingAsyncRequestProducer implements HttpAsyncRequestProducer {
    final HttpAsyncRequestProducer requestProducer;
    final HttpContext context;
    final Subsegment subsegment;

    TracingAsyncRequestProducer(
        HttpAsyncRequestProducer requestProducer, HttpContext context, Subsegment subsegment) {
      this.requestProducer = requestProducer;
      this.context = context;
      this.subsegment = subsegment;
    }

    @Override
    public void close() throws IOException {
      requestProducer.close();
    }

    @Override
    public HttpHost getTarget() {
      return requestProducer.getTarget();
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
      return requestProducer.generateRequest();
    }

    @Override
    public void produceContent(ContentEncoder encoder, IOControl io) throws IOException {
      requestProducer.produceContent(encoder, io);
    }

    @Override
    public void requestCompleted(HttpContext context) {
      requestProducer.requestCompleted(context);
    }

    @Override
    public void failed(Exception ex) {
      XRayManager.endSegment(subsegment, ex);
      requestProducer.failed(ex);
    }

    @Override
    public boolean isRepeatable() {
      return requestProducer.isRepeatable();
    }

    @Override
    public void resetRequest() throws IOException {
      requestProducer.resetRequest();
    }
  }

  final class TracingAsyncResponseConsumer<T> implements HttpAsyncResponseConsumer<T> {
    final HttpAsyncResponseConsumer<T> responseConsumer;
    final HttpContext context;
    final Subsegment subsegment;

    TracingAsyncResponseConsumer(
        HttpAsyncResponseConsumer<T> responseConsumer, HttpContext context, Subsegment subsegment) {
      this.responseConsumer = responseConsumer;
      this.context = context;
      this.subsegment = subsegment;
    }

    @Override
    public void responseReceived(HttpResponse response) throws IOException, HttpException {
      responseConsumer.responseReceived(response);
      subsegment.putAnnotation(Field.STATUS.getName(), response.getStatusLine().getStatusCode());
      subsegment.putAnnotation("statusTest", response.getStatusLine().getReasonPhrase());
      if (subsegment.end()) {
        XRayManager.getRecorder().sendSegment(subsegment.getParentSegment());
      } else {
        if (XRayManager.getRecorder()
            .getStreamingStrategy()
            .requiresStreaming(subsegment.getParentSegment())) {
          XRayManager.getRecorder()
              .getStreamingStrategy()
              .streamSome(
                  subsegment.getParentSegment(), XRayManager.getRecorder().getEmitter());
        }
        XRayManager.newSegmentContextExecutor(subsegment.getParentSegment());
      }
    }

    @Override
    public void consumeContent(ContentDecoder decoder, IOControl ioctrl) throws IOException {
      responseConsumer.consumeContent(decoder, ioctrl);
    }

    @Override
    public void responseCompleted(HttpContext context) {

      responseConsumer.responseCompleted(context);
    }

    @Override
    public void failed(Exception ex) {
      subsegment.addException(ex);
      if (subsegment.end()) {
        XRayManager.getRecorder().sendSegment(subsegment.getParentSegment());
      } else {
        if (XRayManager.getRecorder()
            .getStreamingStrategy()
            .requiresStreaming(subsegment.getParentSegment())) {
          XRayManager.getRecorder()
              .getStreamingStrategy()
              .streamSome(
                  subsegment.getParentSegment(), XRayManager.getRecorder().getEmitter());
        }
        XRayManager.newSegmentContextExecutor(subsegment.getParentSegment());
      }
      responseConsumer.failed(ex);
    }

    @Override
    public Exception getException() {
      return responseConsumer.getException();
    }

    @Override
    public T getResult() {
      return responseConsumer.getResult();
    }

    @Override
    public boolean isDone() {
      return responseConsumer.isDone();
    }

    @Override
    public void close() throws IOException {
      responseConsumer.close();
    }

    @Override
    public boolean cancel() {
      return responseConsumer.cancel();
    }
  }
}
