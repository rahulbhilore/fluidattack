import { Dispatcher } from "flux";
import _ from "underscore";
import Logger from "../utils/Logger";

let id = 0;

/**
 * @class
 * @extends Dispatcher
 */
class AppDispatcher {
  /**
   * dispatcher {Dispatcher} - flux::Dispatcher instance
   * actionIsCurrentlyProcessing {Boolean} - true if actions is processing, false otherwise
   * actionsQueue {Array} - set of actions sent to AppDispatcher
   */
  constructor() {
    this.dispatcher = new Dispatcher();
    this.actionIsCurrentlyProcessing = false;
    this.actionsQueue = [];
  }

  /**
   * Starts processing of actionsQueue
   * @returns {Boolean} - is action dispatched or delayed
   */
  startProcessing() {
    this.actionIsCurrentlyProcessing = true;
    while (this.actionsQueue.length > 0) {
      if (this.dispatcher.isDispatching()) {
        setTimeout(() => {
          this.startProcessing();
        }, 100);
        return false;
      }
      const nextActionInQueue = this.actionsQueue.shift();
      id += 1;
      const actionToLog = _.clone(nextActionInQueue);
      if (actionToLog.hideValue && actionToLog.value) {
        actionToLog.value = actionToLog.value.replace(/./g, "*");
      }
      Logger.addEntry("ACTION", _.extend(actionToLog, { actionId: id }));
      this.dispatcher.dispatch(nextActionInQueue);
    }
    this.actionIsCurrentlyProcessing = false;
    return true;
  }

  /**
   * Dispatch action to stores if there is no currently processing actions.
   * Otherwise - just insert into actionsQueue
   * @param payload {Object}
   */
  dispatch(payload) {
    this.actionsQueue.push(payload);
    // TODO: is it required?
    /* if (!this.actionIsCurrentlyProcessing) { */
    this.startProcessing();
    /* } */
  }

  /**
   * Register Dispatcher listener
   * All stores should be registered in AppDispatcher!
   * @param callback {Function}
   */
  register(callback) {
    return this.dispatcher.register(callback);
  }
}

export default new AppDispatcher();
