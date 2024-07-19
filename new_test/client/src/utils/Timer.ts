import MainFunctions from "../libraries/MainFunctions";
import Logger from "../utils/Logger";

export default class Timer {
  _id: string;

  initTime: number;

  blockName: string;

  constructor(name?: string, id?: string) {
    this._id = id || MainFunctions.guid();
    this.blockName = name || `timer_${this._id}`;
    this.initTime = Date.now();
  }

  log() {
    Logger.addEntry(this.blockName, `Elapsed ${Date.now() - this.initTime} ms`);
  }
}
