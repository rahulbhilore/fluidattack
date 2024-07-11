package com.graebert.storage.util.async;

import com.amazonaws.xray.entities.Segment;
import java.util.function.Consumer;

/**
 * Interface describes 2 methods to execute code blocks
 */
public interface AsyncRunner {
  /**
   * Execute provided code with xray blocking segment
   * @param consumer - is a consumer that accepts newly created blocking segment
   */
  void run(Consumer<Segment> consumer);

  /**
   * Execute provided block of code without xray segment
   * @param runnable - is a runnable block of code
   */
  void runWithoutSegment(Runnable runnable);
}
