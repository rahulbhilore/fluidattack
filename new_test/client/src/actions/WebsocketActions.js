import * as constants from "../constants/WebsocketConstants";
import AppDispatcher from "../dispatcher/AppDispatcher";
import MainFunctions from "../libraries/MainFunctions";

class WebsocketActions {
  /**
   * Connect to given websocket URL
   * @param url {String}
   * @param socketType ['USER'|'FILE']
   */
  static connect(url, socketType) {
    if ((url || "").length > 0 && (socketType || "").length > 0) {
      const newSocketId = MainFunctions.guid();
      AppDispatcher.dispatch({
        actionType: constants.INIT_CONNECTION,
        socketType,
        url,
        socketId: newSocketId
      });
      return newSocketId;
    }
    return null;
  }

  /**
   * Disconnect from websocket
   * @param socketId {String}
   * @param [clearState] {Boolean} - should wsStore state be cleared or not
   *
   */
  static disconnect(socketId, clearState) {
    AppDispatcher.dispatch({
      actionType: constants.CLOSE_CONNECTION,
      socketId,
      clearState
    });
  }

  /**
   * Send message through websocket
   * @param socketId {String}
   * @param message {Object|String}
   */
  static sendMessage(socketId, message) {
    AppDispatcher.dispatch({
      actionType: constants.SEND_MESSAGE,
      socketId,
      message
    });
  }

  /**
   * Handle message sent by websocket
   * @param socketId {String}
   * @param message {Object|String}
   */
  static handleMessage(socketId, message) {
    AppDispatcher.dispatch({
      actionType: constants.HANDLE_MESSAGE,
      socketId,
      message
    });
  }

  /**
   * Handle error sent by websocket
   * @param socketId {String}
   * @param error {Object|String}
   */
  static handleError(socketId, error) {
    AppDispatcher.dispatch({
      actionType: constants.HANDLE_ERROR,
      socketId,
      error
    });
  }

  /**
   * Handle close initiated not by KUDO UI
   * @param socketId {String}
   * @param event {Object|String}
   */
  static handleClose(socketId, event) {
    AppDispatcher.dispatch({
      actionType: constants.HANDLE_CLOSE,
      socketId,
      event
    });
  }

  /**
   * Clear socket info
   * @param socketId
   */
  static clearState(socketId) {
    AppDispatcher.dispatch({
      actionType: constants.CLEAR_STATE,
      socketId
    });
  }

  /**
   * Waits till file is saved on server
   * and newVersion message received
   */
  static awaitForNewVersion() {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: constants.AWAIT_FOR_NEW_VERSION,
        resolve,
        reject
      });
    });
  }
}

export default WebsocketActions;
