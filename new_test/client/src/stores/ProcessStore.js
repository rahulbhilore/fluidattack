import EventEmitter from "events";
import { Map } from "immutable";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ProcessConstants from "../constants/ProcessContants";

class ProcessStore extends EventEmitter {
  constructor() {
    super();
    this.dispatcherIndex = AppDispatcher.register(this._handleAction);
    this._processes = new Map();
  }

  isProcess = id => this._processes.has(id);

  getProcess = id => _.clone(this._processes.get(id));

  getProcessesSize = () => this._processes.size;

  getAllProcessesByType = type => {
    this._processes.values();

    const suitableProcesses = [];

    // eslint-disable-next-line no-restricted-syntax
    for (const process of this._processes.values()) {
      if (process.type === type) suitableProcesses.push(process);
    }

    return suitableProcesses;
  };

  _modify = (id, data) => {
    const currentProcess = this.getProcess(id);
    if (!currentProcess) return false;
    this._processes = this._processes.set(id, _.extend(currentProcess, data));
    return true;
  };

  _handleAction = action => {
    if (action.actionType.indexOf(ProcessConstants.constantPrefix) === -1)
      return;

    switch (action.actionType) {
      case ProcessConstants.START: {
        const newProcess = {
          id: action.id,
          type: action.type,
          status: ProcessConstants.START,
          owner: action.owner,
          ...action.otherData
        };

        this._processes = this._processes.set(action.id, newProcess);

        if (!action.fireEvent) break;

        this.emit(action.id, newProcess);
        this.emit(ProcessConstants.START + action.id, newProcess);
        break;
      }
      case ProcessConstants.END: {
        const currentProcess = this.getProcess(action.id);

        if (!currentProcess) break;

        this._processes = this._processes.delete(action.id);

        if (!action.fireEvent) break;

        currentProcess.status = ProcessConstants.END;

        this.emit(action.id, currentProcess);
        this.emit(ProcessConstants.END + action.id, currentProcess);
        break;
      }
      case ProcessConstants.STEP: {
        if (
          !this._modify(action.id, {
            value: action.value,
            status: ProcessConstants.STEP
          })
        )
          break;

        if (!action.fireEvent) break;

        const currentProcess = this.getProcess(action.id);

        this.emit(action.id, currentProcess);
        this.emit(ProcessConstants.STEP + action.id, currentProcess);
        break;
      }
      case ProcessConstants.MODIFY: {
        this._modify(action.id, action.data);

        if (!action.fireEvent) break;

        const currentProcess = this.getProcess(action.id);

        this.emit(action.id, currentProcess);
        this.emit(ProcessConstants.MODIFY + action.id, currentProcess);
        break;
      }
      default:
    }
  };

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  addChangeListener(eventType, callback) {
    this.on(eventType, callback);
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  removeChangeListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }
}

ProcessStore.dispatchToken = null;
const processStore = new ProcessStore();

export default processStore;
