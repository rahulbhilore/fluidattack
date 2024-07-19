// DK: This is very bad, but I don't want to find new proper TS config to make it work
/* eslint-disable */
import { pack } from "jsonpack";
import MainFunctions from "../libraries/MainFunctions";
import Storage from "./Storage";

const isCommander = navigator.userAgent.indexOf("ARES Commander") > -1;

interface ILoggerEntry {
  _id: number;
  offset: number;
  type: string;
  entryData: any;
}

class Logger {
  transport: any;

  data: ILoggerEntry[];

  currentLogEntryId: number;

  lastActionTime: number;

  initTime: number;

  _originalErrorHandler: (message?: any, ...optionalParams: any[]) => void;

  _originalWarningHandler: (message?: any, ...optionalParams: any[]) => void;

  constructor(_transport: any) {
    this.transport = _transport;
    this.data = [];
    this.currentLogEntryId = 0;
    this.lastActionTime = Date.now();
    this.initTime = Date.now();
    this._originalErrorHandler = console.error;
    this._originalWarningHandler = console.warn;

    // don't use arrow functions here - they doesn't support "arguments to array feature"
    window.onerror = (message, source, line, col, error) => {
      this.addEntry("ERROR", {
        message,
        source,
        line,
        col,
        error,
      });
    };
  }

  addEntry(type: string, ...entryData: any) {
    this.currentLogEntryId += 1;
    const currentTime = Date.now();
    const newOffset = currentTime - this.lastActionTime;
    this.lastActionTime = currentTime;
    const newEntry = {
      type,
      entryData,
      _id: this.currentLogEntryId,
      offset: newOffset,
    };
    this.data.push(newEntry);
    if (isCommander === true) {
      console.log("[ ACL ]", JSON.stringify(newEntry.entryData));
    } else if (process.env.NODE_ENV === "development") {
      if (type === "ERROR") {
        this._originalErrorHandler.apply(console, newEntry.entryData);
      } else if (type === "WARNING") {
        this._originalWarningHandler.apply(console, newEntry.entryData);
      } else {
        this.transport.log.apply(
          this,
          [` { ${type} } `].concat(newEntry.entryData)
        );
      }
    }
  }

  getCurrentSessionLogs() {
    return this.data;
  }

  // TODO: replace with actual schemas once stores are refactored to ts
  getFormattedLogs(applicationInfo: any, userInfo: any) {
    const endTime = Date.now();
    return {
      _id: MainFunctions.guid(),
      time: endTime,
      data: {
        time: endTime,
        meta: {
          applicationInfo,
          instanceURL: location.origin,
          sessionTime: endTime - this.initTime,
          userInfo: {
            session: Storage.store("session"),
            user: userInfo,
          },
        },
        requests: [],
        actions: this.data,
      },
    };
  }

  saveLogsLocally(applicationInfo: any, userInfo: any) {
    const fullInfo = this.getFormattedLogs(applicationInfo, userInfo);
    const fileName = "sessionLog.txt";

    const saveDate = pack(JSON.parse(JSON.stringify(fullInfo)));

    const blob = new Blob([saveDate], { type: "text" });
    const e = document.createEvent("MouseEvents");
    const a = document.createElement("a");

    a.download = fileName;
    a.href = window.URL.createObjectURL(blob);
    a.dataset.downloadurl = ["text/json", a.download, a.href].join(":");
    e.initMouseEvent(
      "click",
      true,
      false,
      window,
      0,
      0,
      0,
      0,
      0,
      false,
      false,
      false,
      false,
      0,
      null
    );
    a.dispatchEvent(e);
  }

  encodeLogs(applicationInfo: any, userInfo: any): string {
    return pack(
      JSON.parse(
        JSON.stringify(this.getFormattedLogs(applicationInfo, userInfo))
      )
    );
  }
}

const singleLogger = new Logger(console);

// @ts-ignore
window.Logger = singleLogger;

export default singleLogger;
/* eslint-enable */
