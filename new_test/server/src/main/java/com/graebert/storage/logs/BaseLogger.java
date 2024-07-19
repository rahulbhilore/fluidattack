package com.graebert.storage.logs;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BaseLogger {
  private static final Logger LOGGER = LogManager.getRootLogger();
  protected static final Boolean WITH_CONSOLE_LOG = true;

  protected LogGroup getLogGroup() {
    return LogGroup.UNNAMED;
  }

  // color schema lo highlight some important info in system console
  protected String getAnsiPrefix() {
    return "";
  }

  protected String getAnsiPostfix() {
    return AnsiColors.ANSI_RESET.getColor();
  }

  public static void logToConsole(String message, AnsiColors color) {
    if (BaseLogger.WITH_CONSOLE_LOG) {
      System.out.println(color.getColor().concat(message).concat(AnsiColors.ANSI_RESET.getColor()));
    }
  }

  public void logToConsole(String message) {
    if (BaseLogger.WITH_CONSOLE_LOG) {
      System.out.println(getAnsiPrefix().concat(message).concat(getAnsiPostfix()));
    }
  }

  private void log(
      Level level, String messageType, String messageLog, Throwable throwable, int depth) {
    String message = String.format(
        "[%s] {%s} %s: %s", getLogGroup().name(), getLogLocation(depth), messageType, messageLog);

    Optional.ofNullable(throwable)
        .ifPresentOrElse(
            t -> BaseLogger.LOGGER.log(level, message, t),
            () -> BaseLogger.LOGGER.log(level, message));

    logToConsole(message);
  }

  protected void log(Level level, String messageType, String messageLog, Throwable throwable) {
    log(level, messageType, messageLog, throwable, 5);
  }

  protected void log(Level level, String messageType, String messageLog) {
    log(level, messageType, messageLog, null, 5);
  }

  protected static String getReadableStackTrace() {
    try {
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

      return IntStream
          // do not take this.func and Thead
          .range(2, stackTrace.length - 1)
          .filter(i -> stackTrace[i].getClassName().contains("graebert")
              || (stackTrace[i - 1].getClassName().contains("graebert")
                  && !stackTrace[i].getClassName().contains("graebert")))
          .mapToObj(i -> stackTrace[i].getClassName().contains("graebert")
              ? stackTrace[i]
                      .getClassName()
                      .substring(stackTrace[i].getClassName().lastIndexOf(".") + 1)
                  + ":" + stackTrace[i].getMethodName()
              : "[...]")
          .collect(Collectors.joining(" <- "));
    } catch (Exception exception) {
      return "genError";
    }
  }

  protected static String getLogLocation(int depth) {
    StackTraceElement[] traceElements = Thread.currentThread().getStackTrace();

    int index = IntStream.range(4, traceElements.length - 1)
        .filter(i -> traceElements[i].getClassName().contains("graebert")
            && !traceElements[i].getClassName().contains("Logger"))
        .findFirst()
        .orElse(depth);

    return traceElements[index]
            .getClassName()
            .substring(traceElements[index].getClassName().lastIndexOf(".") + 1)
        + ":" + traceElements[index].getMethodName();
  }

  public void logInfo(String messageType, String messageLog) {
    log(Level.INFO, messageType, messageLog);
  }

  public void logError(String messageType, String messageLog) {
    log(Level.ERROR, messageType, messageLog);
  }

  public void logError(String messageType, String messageLog, Throwable throwable) {
    log(Level.ERROR, messageType, messageLog, throwable);
  }

  public void logWarn(String messageType, String messageLog) {
    log(Level.WARN, messageType, messageLog);
  }

  public void logDebug(String messageType, String messageLog) {
    log(Level.DEBUG, messageType, messageLog);
  }
}
