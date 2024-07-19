/* eslint-disable */
// TableStore has to be removed anyway

import * as TimSort from "timsort";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import TableConstants from "../constants/TableConstants";
import { EventEmitter } from "events";
import assign from "object-assign";
import {
  sortByAccess,
  sortByModification,
  sortByName,
  sortBySize,
} from "../utils/FileSort";
import { SortDirection } from "react-virtualized";

export const CHANGE_EVENT = "change";
export const FIELD_EVENT = "field";
export const SELECT_EVENT = "select";
export const RECALCULATE_EVENT = "RECALCULATE_EVENT";
export const CONTENT_CHANGE = "CONTENT_CHANGE";
export const PROCESS_EVENT = "PROCESS_EVENT";
let lastChangeEvent = Date.now();
let isEventProccessing = false;
// TODO: fix
const UIEventTime = 200;
const selected = {};
const _tables = {};
let _focusedTable = null;

/**
 * Save table configuration
 * @param tableId {String}
 * @param configuration {Object}
 */
function saveTableConfiguration(tableId, configuration) {
  if (!configuration.hasOwnProperty("fields")) {
    console.error("No fields property in table configuration");
  }
  if (!configuration.hasOwnProperty("type")) {
    console.error("No type property in table configuration");
  }
  _tables[tableId] = _.defaults(configuration, {
    actionsRun: 0,
    multiSelected: true,
  });
}

// --SELECT FUNCTIONS--//

/**
 * Select all entities in table
 */
function selectAll(tableId) {
  selected[tableId] = _tables[tableId].results;
}

/**
 * Remove selection on all selected entities
 */
function clearSelection() {
  Object.keys(selected).forEach((tableId) => {
    selected[tableId] = [];
  });
}

/**
 * Select only 1 object
 * @param tableId
 * @param object {Object}
 */
function saveOneSelected(tableId, object) {
  clearSelection();
  selected[tableId] = [object];
}

/**
 * If object is selected - deselect it, otherwise - push to selected array
 * @param tableId
 * @param object {Object}
 * @param force {Boolean} Add to selected objects array without checking
 */
function addToSelect(tableId, object, force) {
  // if not force - check if it has been selected previously
  if (!force) {
    const res = _.findWhere(selected[tableId], { id: object.id });
    if (!res) {
      selected[tableId].push(object);
    } else {
      selected[tableId] = selected[tableId].filter(
        (element) => element.id !== object.id
      );
    }
  } else {
    // if force - just push it
    selected[tableId].push(object);
  }
  selected[tableId] = _.uniq(selected[tableId]);
}

/**
 * If object is selected - deselect it, otherwise - push to selected array
 * @param tableId
 * @param object {Object}
 * @param force {Boolean} Add to selected objects array without checking
 */
function multiSelect(tableId, objects) {
  clearSelection();
  selected[tableId] = objects;
}

//! --SELECT FUNCTIONS--!//

function setFocusOnTable(tableId) {
  if (TableStore.isTableRegistered(tableId)) {
    _focusedTable = tableId;
  } else {
    _focusedTable = null;
  }
}

/**
 * Sort function
 * @param a first item
 * @param b second item
 * @param vtype {String} if set to num - convert a and b to floats
 * @param mod {Number} ascending or descending
 * @returns {Number}
 */
function cmp(a, b, vtype, mod) {
  if (vtype == "num") {
    a = parseFloat(a);
    b = parseFloat(b);
  }
  if (a > b) return -mod;
  if (a < b) return mod;
  return 0;
}

/**
 * Start editing field in table
 * @param tableId
 * @param entityId {String}
 * @param fieldName {String}
 * @param stateFlag {Boolean} flag determines whether field edit has started or ended
 */
function toggleFieldEdit(tableId, entityId, fieldName, stateFlag) {
  const entityToPerform = TableStore.getRowInfo(tableId, entityId);
  if (entityToPerform) {
    if (_tables[tableId].fields[fieldName].edit) {
      const editValue = { edit: {} };
      editValue.edit[fieldName] = stateFlag;
      if (
        entityToPerform.actions &&
        entityToPerform.actions.edit &&
        entityToPerform.actions.edit[fieldName] !== undefined &&
        entityToPerform.actions.edit[fieldName] !== stateFlag
      ) {
        // if entity had different flag previously
        _tables[tableId].actionsRun += stateFlag ? 1 : -1;
      }
      entityToPerform.actions = _.extend(entityToPerform.actions, editValue);
    } else {
      console.error(
        "Field",
        fieldName,
        "has edit flag false. See /stores/TableStore -> toggleFieldEdit"
      );
    }
  } else {
    console.error(
      "Entity for editing not found(id:",
      entityId,
      "). See /stores/TableStore -> toggleFieldEdit"
    );
  }
}

/**
 * Send event to specific row
 * @param tableId
 * @param entityId {String} where to send
 * @param event {Event} which event to send
 */
function passEventToRow(tableId, entityId, event) {
  const entityToPerform = TableStore.getRowInfo(tableId, entityId);
  if (entityToPerform) {
    entityToPerform.lastEvent = event;
  } else {
    console.error(
      "Entity for editing not found(id:",
      entityId,
      "). See /stores/TableStore -> passEventToRow"
    );
  }
}

/**
 * Add entity to table
 * @param tableId {String}
 * @param entityData {Object}
 */
function addEntity(tableId, entityData) {
  const currentTimestamp = Date.now();
  _tables[tableId].results.unshift(
    _.defaults(entityData, {
      creationDate: currentTimestamp,
      updateDate: currentTimestamp,
      isOwner: true,
      owner: "",
      changer: "",
      ownerId: "",
      actions: {
        edit: {
          name: false,
          comment: false,
        },
      },
      lastUpdate: currentTimestamp,
    })
  );
}

/**
 * Modifies entity in table (e.g. in case of uploading)
 * @param tableId {String}
 * @param entityId {String}
 * @param newEntityData {Object}
 * @param [omitProperties] {Array}
 */
function modifyEntity(tableId, entityId, newEntityData, omitProperties) {
  // TODO: fix removing of process
  let currentElement = TableStore.getRowInfo(tableId, entityId);
  if (currentElement) {
    const updateHash = Date.now();
    currentElement = _.extend(_.extend(currentElement, newEntityData), {
      lastUpdate: updateHash,
      updateHash,
    });
    _.each(omitProperties, (propertyName) => {
      currentElement[propertyName] = null;
    });
  }
}

/**
 * Remove entity with specified id from table
 * @param tableId {String}
 * @param entityId {String}
 */
function deleteEntity(tableId, entityId) {
  if (!tableId || !entityId || _.isEmpty(_tables)) {
    return;
  }
  _tables[tableId].results = _.filter(
    _tables[tableId].results,
    (elem) => elem.id !== entityId && elem._id !== entityId
  );
}

/**
 * Sort table entities by specific field
 * @param tableId {String}
 * @param sortField {String} field name
 * @param forcedOrder {Boolean} determine order in which sort should be done
 */
function sortTable(tableId, sortField, forcedOrder) {
  let order = "desc";
  if (forcedOrder !== undefined) {
    order = forcedOrder;
  } else {
    order =
      _tables[tableId].fields[sortField].order === "desc" ? "asc" : "desc";
  }
  _tables[tableId].orderedBy = sortField;
  _tables[tableId].fields[sortField].order = order;
  window.ARESKudoMainTableSort = { orderedBy: sortField, order };
  const finalSortOrder =
    order === "asc" ? SortDirection.ASC : SortDirection.DESC;
  new Promise((resolve) => {
    TimSort.sort(_tables[tableId].results, (a, b) => {
      if (sortField === "modified")
        return sortByModification(a, b, finalSortOrder);
      if (sortField === "owner") return sortByAccess(a, b, finalSortOrder);
      if (sortField === "size") return sortBySize(a, b, finalSortOrder);
      return sortByName(a, b, finalSortOrder);
    });
    if (finalSortOrder === SortDirection.DESC)
      _tables[tableId].results = _tables[tableId].results.reverse();
    resolve();
  }).then(() => {
    TableStore.emitChangeEvent(true);
  });
}

/**
 * Locks the table from any interruprion
 */
function setTableLock(tableId) {
  if (Object.prototype.hasOwnProperty.call(_tables, tableId) === true) {
    _tables[tableId].actionsRun += 1;
  }
}

/**
 * Release the lock on table
 */
function releaseTableLock(tableId) {
  if (Object.prototype.hasOwnProperty.call(_tables, tableId) === true) {
    _tables[tableId].actionsRun -= 1;
  }
}

var TableStore = assign({}, EventEmitter.prototype, {
  registerTable(tableId) {
    if (!Object.keys(_tables).length) {
      _focusedTable = tableId;
    }
    if (Object.prototype.hasOwnProperty.call(_tables, tableId) === false) {
      _tables[tableId] = {};
      selected[tableId] = [];
    }
  },

  unregisterTable(tableId) {
    if (Object.prototype.hasOwnProperty.call(_tables, tableId) === true) {
      delete _tables[tableId];
      delete selected[tableId];
      _focusedTable = Object.keys(_tables)[0] || null;
    }
  },

  getRegisteredTables() {
    return _.keys(_tables);
  },

  isTableRegistered(tableId) {
    return !!(_.indexOf(_.keys(_tables), tableId) + 1);
  },

  /**
   * Get tableId of currently active table
   * @returns {String||null}
   */
  getFocusedTable() {
    return _focusedTable;
  },

  /**
   * Check if entity with name exists in table.
   * @param tableId
   * @param name {String} name to find
   * @param oldName {String} name to ignore
   * @param type {String} entity type. Different types with the same name can co-exist
   * @returns {boolean}
   */
  isInList(tableId, name, oldName, type) {
    let results = [];
    if (_tables[tableId]) {
      results = _tables[tableId].results || [];
    }
    return !!_.find(
      results,
      (obj) =>
        obj.name.toLowerCase() === name.toLowerCase() &&
        obj.name.toLowerCase() !== oldName.toLowerCase() &&
        obj.type === type
    );
  },

  /**
   * Check if target is selected
   * @param tableId
   * @param entityId {String}
   * @returns {Boolean}
   */
  isSelected(tableId, entityId) {
    return !!_.where(selected[tableId], { id: entityId }).length;
  },

  /**
   * Get currently selected items
   * @returns {Array}
   */
  getSelection(tableId) {
    return selected[tableId] || [];
  },

  /**
   * Get table configuration
   * @returns {Object}
   */
  getTable(tableId) {
    return _tables[tableId];
  },

  emitChangeEvent(forceEvent) {
    if (forceEvent === true) {
      this.emit(CHANGE_EVENT);
      return true;
    }
    if (isEventProccessing) return false;
    isEventProccessing = true;
    if (new Date().getTime() - lastChangeEvent >= UIEventTime) {
      this.emit(CHANGE_EVENT);
    } else if (!window.tableStoreEventEmitterTimer) {
      window.tableStoreEventEmitterTimer = setInterval(() => {
        if (
          !isEventProccessing &&
          new Date().getTime() - lastChangeEvent >= UIEventTime
        ) {
          isEventProccessing = true;
          this.emit(CHANGE_EVENT);
          lastChangeEvent = new Date().getTime();
          isEventProccessing = false;
          clearInterval(window.tableStoreEventEmitterTimer);
          delete window.tableStoreEventEmitterTimer;
        }
      }, UIEventTime);
    }
    lastChangeEvent = new Date().getTime();
    isEventProccessing = false;
  },

  emitSelect() {
    this.emit(SELECT_EVENT);
  },

  /**
   * Check if any action is currently performing on table
   * @returns {boolean}
   */
  performingAnyAction(tableId) {
    return !!_tables[tableId].actionsRun;
  },

  /**
   * Get info of specific entity
   * @param tableId
   * @param id {String}
   */
  getRowInfo(tableId, id) {
    if (!_tables[tableId]) return null;
    return _.findWhere(_tables[tableId].results, { id });
  },
});

TableStore.setMaxListeners(0);

// Register callback to handle all updates
TableStore.dispatcherIndex = AppDispatcher.register((action) => {
  switch (action.actionType) {
    case TableConstants.REGISTER_TABLE:
      TableStore.registerTable(action.tableId);
      break;
    case TableConstants.UNREGISTER_TABLE:
      TableStore.unregisterTable(action.tableId);
      break;
    case TableConstants.SAVE_CONFIGURATION:
      saveTableConfiguration(action.tableId, action.configuration);
      TableStore.emitChangeEvent(true);
      break;
    case TableConstants.SELECT_OBJECT:
      saveOneSelected(action.tableId, action.objectInfo);
      TableStore.emit(SELECT_EVENT);
      break;
    case TableConstants.MULTI_SELECT:
      multiSelect(action.tableId, action.objects);
      TableStore.emit(SELECT_EVENT);
      break;
    case TableConstants.ADD_TO_SELECT:
      addToSelect(action.tableId, action.objectInfo, action.force);
      TableStore.emit(SELECT_EVENT);
      break;
    case TableConstants.CLEAR_SELECT:
      clearSelection();
      TableStore.emit(SELECT_EVENT);
      break;
    case TableConstants.SELECT_ALL:
      selectAll(action.tableId);
      TableStore.emit(SELECT_EVENT);
      break;
    case TableConstants.SORT_TABLE:
      sortTable(action.tableId, action.sortField, action.order);
      break;
    case TableConstants.EDIT_FIELD:
      toggleFieldEdit(
        action.tableId,
        action.entityId,
        action.fieldName,
        action.stateFlag
      );
      TableStore.emit(FIELD_EVENT);
      break;
    case TableConstants.EMIT_KEY:
      passEventToRow(action.tableId, action.entityId, action.event);
      TableStore.emit(FIELD_EVENT);
      break;
    case TableConstants.SET_TABLE_LOCK:
      setTableLock(action.tableId);
      TableStore.emitChangeEvent(true);
      break;
    case TableConstants.RELEASE_TABLE_LOCK:
      releaseTableLock(action.tableId);
      TableStore.emitChangeEvent(true);
      break;
    case TableConstants.FOCUS_TABLE:
      setFocusOnTable(action.tableId);
      TableStore.emitChangeEvent(true);
      break;
    case TableConstants.ADD_ENTITY:
      addEntity(action.tableId, action.entityData);
      TableStore.emit(CONTENT_CHANGE);
      break;
    case TableConstants.MODIFY_ENTITY:
      modifyEntity(
        action.tableId,
        action.entityId,
        action.newEntityData,
        action.omitProperties
      );
      TableStore.emit(CONTENT_CHANGE);
      TableStore.emit(FIELD_EVENT);
      break;
    case TableConstants.DELETE_ENTITY:
      deleteEntity(action.tableId, action.entityId);
      TableStore.emit(CONTENT_CHANGE);
      break;
    case TableConstants.RECALCULATE_DIMENSIONS:
      TableStore.emit(RECALCULATE_EVENT);
      break;
    case TableConstants.PROCESS_START:
      TableStore.emit(PROCESS_EVENT + action.id, {
        id: action.processId || action.id || "NA",
        name: action.type,
        state: "start",
      });
      break;
    case TableConstants.PROCESS_END:
      TableStore.emit(PROCESS_EVENT + action.id, {
        id: action.processId || action.id || "NA",
        name: action.type,
        state: "end",
      });
      break;
    case TableConstants.PROCESS_STEP:
      TableStore.emit(PROCESS_EVENT + action.id, {
        id: action.processId || action.id || "NA",
        name: action.type,
        state: "step",
        value: action.value,
      });
      break;
    default:
    // no op
  }
});

export default TableStore;
