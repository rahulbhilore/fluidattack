import Logger from "../../../utils/Logger";

export default class OpenTimeTracker {
  static PREFIX = "FILE_OPEN_TIME";

  start() {
    this.startTime = Date.now();
    Logger.addEntry(OpenTimeTracker.PREFIX, "started", this.startTime);
  }

  logEvent(eventType) {
    Logger.addEntry(
      OpenTimeTracker.PREFIX,
      eventType,
      Date.now() - this.startTime
    );
  }
}
