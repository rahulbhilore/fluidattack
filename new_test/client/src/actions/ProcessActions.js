import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ProcessConstants from "../constants/ProcessContants";

export default class ProcessActions {
  static start(id, type, owner, otherData = {}, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: ProcessConstants.START,
      id,
      type,
      owner: owner || id,
      otherData,
      fireEvent
    });
  }

  static end(id, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: ProcessConstants.END,
      id,
      fireEvent
    });
  }

  static step(id, value, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: ProcessConstants.STEP,
      id,
      value,
      fireEvent
    });
  }

  static modify(id, data, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: ProcessConstants.MODIFY,
      id,
      data,
      fireEvent
    });
  }
}
