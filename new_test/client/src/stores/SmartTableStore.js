import EventEmitter from "events";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as SmartTableConstants from "../constants/SmartTableConstants";

export const SELECT_EVENT = "SELECT_EVENT";
export const DIMENSIONS_EVENT = "DIMENSIONS_EVENT";
export const OPEN_EVENT = "OPEN_EVENT";
export const SCROLL_TO_ID_EVENT = "SCROLL_TO_ID_EVENT";
export const SCROLL_TO_TOP_EVENT = "SCROLL_TO_TOP_EVENT";

class SmartTableStore extends EventEmitter {
  constructor() {
    super();
    this._tables = {};
    this.tableIds = [];
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  getTableInfo(tableId) {
    return this._tables[tableId] || {};
  }

  getTableIdByType(tableType) {
    return _.findKey(this._tables, elem => elem.type === tableType);
  }

  getTableInfoByType(tableType) {
    return this.getTableInfo(this.getTableIdByType(tableType));
  }

  getSelectedRows(tableId) {
    if (Object.prototype.hasOwnProperty.call(this._tables, tableId))
      return this._tables[tableId].selectedRows;
    return [];
  }

  getSelectedRowsByTableType(tableType) {
    return this.getSelectedRows(
      _.findKey(this._tables, elem => elem.type === tableType)
    );
  }

  deselectAllRowsInTable({ tableId, tableType }) {
    if (tableId) {
      if (Object.prototype.hasOwnProperty.call(this._tables, tableId)) {
        this._tables[tableId].selectedRows = [];
      }
    } else {
      Object.keys(this._tables).forEach(tId => {
        if (!tableType || this._tables[tId].type === tableType) {
          this._tables[tId].selectedRows = [];
        }
      });
    }
  }

  handleAction(action) {
    if (action.actionType.indexOf(SmartTableConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case SmartTableConstants.TABLE_REGISTER:
          this._tables[action.tableId] = {
            type: action.type,
            storage: action.storage,
            accountId: action.accountId,
            selectedRows: []
          };
          this.tableIds.push(action.tableId);
          // no emit required
          break;
        case SmartTableConstants.TABLE_SELECT:
          if (
            Object.prototype.hasOwnProperty.call(this._tables, action.tableId)
          ) {
            if (!_.isArray(action.rowIds)) {
              action.rowIds = [action.rowIds];
            }
            this._tables[action.tableId].selectedRows = action.rowsIds;
          }
          this.emit(SELECT_EVENT + action.tableId);
          break;
        case SmartTableConstants.TABLE_DESELECT:
          if (
            Object.prototype.hasOwnProperty.call(this._tables, action.tableId)
          ) {
            this._tables[action.tableId].selectedRows = this._tables[
              action.tableId
            ].selectedRows.filter(
              rowId => action.rowsIds.indexOf(rowId) === -1
            );
          }
          this.emit(SELECT_EVENT + action.tableId);
          break;
        case SmartTableConstants.TABLE_DESELECT_ALL:
          this.deselectAllRowsInTable({
            tableId: action.tableId,
            tableType: action.tableType
          });
          this.emit(SELECT_EVENT + action.tableId);
          break;
        case SmartTableConstants.TABLE_UNREGISTER:
          if (
            Object.prototype.hasOwnProperty.call(this._tables, action.tableId)
          ) {
            delete this._tables[action.tableId];
            this.tableIds = this.tableIds.filter(id => id !== action.tableId);
          }
          break;
        case SmartTableConstants.TABLE_RECALCULATE_DIMENSIONS:
          if (action.tableId) {
            this.emit(DIMENSIONS_EVENT + action.tableId);
          } else {
            this.tableIds.forEach(tableId => {
              this.emit(DIMENSIONS_EVENT + tableId);
            });
          }
          break;
        case SmartTableConstants.TABLE_OPEN_ROW: {
          this.deselectAllRowsInTable({ tableId: action.tableId });
          this.emit(OPEN_EVENT + action.tableId + action.rowId);
          break;
        }
        case SmartTableConstants.SORT_COMPLETED: {
          this.emit(SmartTableConstants.SORT_COMPLETED);
          break;
        }
        case SmartTableConstants.SCROLL_TO_ID: {
          this.emit(SCROLL_TO_ID_EVENT + action.tableId, action.objectId);
          break;
        }
        case SmartTableConstants.SCROLL_TO_TOP: {
          this.emit(SCROLL_TO_TOP_EVENT + action.tableId);
          break;
        }
        default:
          break;
      }
    }
  }
}

SmartTableStore.dispatchToken = null;
const smartTableStore = new SmartTableStore();
smartTableStore.setMaxListeners(0);

export default smartTableStore;
