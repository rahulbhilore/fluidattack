import EventEmitter from "events";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ContextMenuConstants from "../constants/ContextMenuConstants";

const CHANGE_EVENT = "change";

class ContextMenuStore extends EventEmitter {
  constructor() {
    super();
    this.listener = null;
    // lastUpdate is used to be sure that iOs taps are handled correctly
    this.currentState = {
      isVisible: false,
      X: 0,
      Y: 0,
      objectId: null,
      selectedRow: -1,
      isIsolatedObject: false,
      customObjectInfo: {},
      lastUpdate: Date.now(),
      ids: [],
      infoProvider: () => null,
      type: "",
      tableId: null
    };
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  getCurrentInfo() {
    return this.currentState;
  }

  emitChange() {
    this.emit(CHANGE_EVENT);
  }

  /**
   * @param {function} callback
   */
  addChangeListener(callback) {
    this.on(CHANGE_EVENT, callback);
  }

  /**
   * @param {function} callback
   */
  removeChangeListener(callback) {
    this.removeListener(CHANGE_EVENT, callback);
  }

  handleAction(action) {
    if (action.actionType.indexOf(ContextMenuConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case ContextMenuConstants.CONTEXT_SHOW:
          this.currentState = {
            isVisible: true,
            X: action.x,
            Y: action.y,
            objectId: action.objectId,
            selectedRow: -1,
            customObjectInfo: action.customObjectInfo || {},
            type: action.type
          };
          this.currentState.lastUpdate = Date.now();
          this.emitChange();
          break;
        case ContextMenuConstants.CONTEXT_SHOW_MULTIPLE:
          this.currentState = {
            isVisible: true,
            X: action.x,
            Y: action.y,
            ids: action.ids,
            selectedRow: -1,
            infoProvider: action.infoProvider,
            type: action.type,
            tableId: action.tableId
          };
          this.currentState.lastUpdate = Date.now();
          this.emitChange();
          break;
        case ContextMenuConstants.CONTEXT_HIDE:
          this.currentState = {
            isVisible: false,
            X: 0,
            Y: 0,
            objectId: null,
            selectedRow: -1,
            isIsolatedObject: false,
            customObjectInfo: {},
            type: "",
            ids: [],
            infoProvider: () => undefined
          };
          this.currentState.lastUpdate = Date.now();
          this.emitChange();
          break;
        case ContextMenuConstants.CONTEXT_SELECT:
          this.currentState.selectedRow = action.rowId;
          this.currentState.lastUpdate = Date.now();
          this.emitChange();
          break;
        case ContextMenuConstants.CONTEXT_MOVE:
          this.currentState.X = action.x;
          this.currentState.Y = action.y;
          this.emitChange();
          break;
        default:
          break;
      }
    }
  }
}

ContextMenuStore.dispatchToken = null;

export default new ContextMenuStore();
