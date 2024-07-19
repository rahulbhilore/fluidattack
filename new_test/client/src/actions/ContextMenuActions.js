import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ContextMenuConstants from "../constants/ContextMenuConstants";

export default class ContextMenuActions {
  static showMenu(x, y, objectId, customObjectInfo, type) {
    AppDispatcher.dispatch({
      actionType: ContextMenuConstants.CONTEXT_SHOW,
      x,
      y,
      objectId,
      customObjectInfo,
      type
    });
  }

  static showMenuForSelection(x, y, ids, infoProvider, type, tableId) {
    AppDispatcher.dispatch({
      actionType: ContextMenuConstants.CONTEXT_SHOW_MULTIPLE,
      x,
      y,
      ids,
      infoProvider,
      type,
      tableId
    });
  }

  static hideMenu() {
    AppDispatcher.dispatch({
      actionType: ContextMenuConstants.CONTEXT_HIDE
    });
  }

  static selectRow(rowId) {
    AppDispatcher.dispatch({
      actionType: ContextMenuConstants.CONTEXT_SELECT,
      rowId
    });
  }

  static move(x, y) {
    AppDispatcher.dispatch({
      actionType: ContextMenuConstants.CONTEXT_MOVE,
      x,
      y
    });
  }
}
