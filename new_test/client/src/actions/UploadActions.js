import AppDispatcher from "../dispatcher/AppDispatcher";
import * as UploadConstants from "../constants/UploadConstants";
import MainFunctions from "../libraries/MainFunctions";

const UploadActions = {
  /*
  {
        url,
        method,
        body,
        headers
      }
  */
  queueUploads(uploadArray) {
    const toSendArray = uploadArray.map(v => ({
      ...v,
      requestId: MainFunctions.guid()
    }));
    AppDispatcher.dispatch({
      actionType: UploadConstants.QUEUE_UPLOAD,
      data: toSendArray
    });
    return toSendArray;
  },

  setRequestFinalized(requestId, response) {
    AppDispatcher.dispatch({
      actionType: UploadConstants.SET_RESPONSE,
      requestId,
      response
    });
  },

  setRequestError(requestId, error) {
    AppDispatcher.dispatch({
      actionType: UploadConstants.SET_ERROR,
      requestId,
      error
    });
  },

  setRequestInProgress(requestId) {
    AppDispatcher.dispatch({
      actionType: UploadConstants.SET_IN_PROGRESS,
      requestId
    });
  },

  clearList() {
    AppDispatcher.dispatch({
      actionType: UploadConstants.CLEAR_LIST
    });
  }
};

export default UploadActions;
