/**
 * Created by Dima Graebert on 3/7/2017.
 */
import * as constants from "../constants/XenonConnectionConstants";
import AppDispatcher from "../dispatcher/AppDispatcher";

class XenonConnectionActions {
  static connect() {
    AppDispatcher.dispatch({
      actionType: constants.XE_INIT_CONNECTION
    });
  }

  static disconnect() {
    AppDispatcher.dispatch({
      actionType: constants.XE_CLOSE_CONNECTION
    });
  }

  static postMessage(message) {
    AppDispatcher.dispatch({
      message,
      actionType: constants.XE_POST_MESSAGE
    });
  }

  static onMessage(message, origin) {
    AppDispatcher.dispatch({
      message,
      origin,
      actionType: constants.XE_GET_MESSAGE
    });
  }
}

export default XenonConnectionActions;
