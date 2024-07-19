import EventEmitter from "events";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ModalConstants from "../constants/ModalConstants";

export const CHANGE = "CHANGE";
export const KEY_EVENT = "KEY_EVENT";

class ModalStore extends EventEmitter {
  constructor() {
    super();
    this.state = {
      isOpened: false,
      currentDialog: null,
      caption: "",
      params: {}
    };
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  getCurrentInfo() {
    return this.state;
  }

  isDialogOpen() {
    return this.state.isOpened;
  }

  getCurrentDialogType() {
    return this.state.currentDialog;
  }

  hideModal() {
    this.state = {
      isOpened: false,
      currentDialog: null,
      caption: "",
      params: {}
    };
    this.emit(CHANGE);
  }

  saveDialogInfo(type, caption, params) {
    this.state = {
      isOpened: true,
      currentDialog: type,
      caption,
      params
    };
    this.emit(CHANGE);
  }

  handleAction(action) {
    if (action.actionType.indexOf(ModalConstants.constantPrefix) > -1) {
      if (action.actionType === ModalConstants.KEY_EVENT) {
        this.emit(KEY_EVENT, action.action, action.event);
      } else if (action.actionType === ModalConstants.HIDE) {
        const { type = undefined } = action;

        if (!type || (type && type === this.state.currentDialog)) {
          this.hideModal();
        }
      } else {
        this.saveDialogInfo(
          action.actionType,
          action.caption,
          _.omit(action, "caption", "actionType")
        );
      }
    }
  }
}
ModalStore.dispatchToken = null;
const modalStore = new ModalStore();
modalStore.setMaxListeners(0);

export default modalStore;
