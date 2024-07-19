import AppDispatcher from "../dispatcher/AppDispatcher";
import * as SmartTableConstants from "../constants/SmartTableConstants";

export default class SmartTableActions {
  static registerTable(tableId, type) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_REGISTER,
      tableId,
      type
    });
  }

  static unregisterTable(tableId) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_UNREGISTER,
      tableId
    });
  }

  /**
   *
   * @param tableId {string} - id of the table
   * @param rowsIds {Array} - ids of rows to select
   */
  static selectRows(tableId, rowsIds) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_SELECT,
      tableId,
      rowsIds
    });
  }

  static deselectRows(tableId, rowsIds) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_DESELECT,
      tableId,
      rowsIds
    });
  }

  static deselectAllRows({ tableId, tableType }) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_DESELECT_ALL,
      tableId,
      tableType
    });
  }

  static recalculateDimensions(tableId) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_RECALCULATE_DIMENSIONS,
      tableId
    });
  }

  static openRow(tableId, rowId) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.TABLE_OPEN_ROW,
      tableId,
      rowId
    });
  }

  static sortCompleted() {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.SORT_COMPLETED
    });
  }

  static scrollToId(tableId, objectId) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.SCROLL_TO_ID,
      tableId,
      objectId
    });
  }

  static scrollToTop(tableId) {
    AppDispatcher.dispatch({
      actionType: SmartTableConstants.SCROLL_TO_TOP,
      tableId
    });
  }
}
