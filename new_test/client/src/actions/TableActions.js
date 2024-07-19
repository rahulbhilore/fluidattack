import AppDispatcher from "../dispatcher/AppDispatcher";
import TableConstants from "../constants/TableConstants";

const TableActions = {
  /**
   * Register table in TableStore
   * @param tableId {String}
   */
  registerTable(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.REGISTER_TABLE,
      tableId
    });
  },

  /**
   * Unregister table in TableStore
   * @param tableId {String}
   */
  unRegisterTable(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.UNREGISTER_TABLE,
      tableId
    });
  },

  /**
   * Save configuration for table (fields, order etc.)
   * @param tableId {String}
   * @param configuration {Object}
   * @param configuration.fields {Object}
   * @param configuration.type {String}
   */
  saveConfiguration(tableId, configuration) {
    AppDispatcher.dispatch({
      actionType: TableConstants.SAVE_CONFIGURATION,
      tableId,
      configuration
    });
  },

  /**
   * Select an object in table
   * @param tableId {String}
   * @param objectInfo {Object}
   */
  selectObject(tableId, objectInfo) {
    AppDispatcher.dispatch({
      actionType: TableConstants.SELECT_OBJECT,
      tableId,
      objectInfo
    });
  },

  /**
   * Add object to current selection
   * @param tableId {String}
   * @param objectInfo {Object}
   * @param force {Boolean}
   */
  addToSelected(tableId, objectInfo, force) {
    AppDispatcher.dispatch({
      actionType: TableConstants.ADD_TO_SELECT,
      tableId,
      objectInfo,
      force
    });
  },

  multiSelect(tableId, objects) {
    AppDispatcher.dispatch({
      actionType: TableConstants.MULTI_SELECT,
      tableId,
      objects
    });
  },

  /**
   * Removes selection from all tables
   */
  removeSelection() {
    AppDispatcher.dispatch({
      actionType: TableConstants.CLEAR_SELECT
    });
  },

  /**
   * Select all objects in table with tableId
   * @param tableId {String}
   */
  selectAll(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.SELECT_ALL,
      tableId
    });
  },

  /**
   * Sort table by some property
   * @param tableId {String}
   * @param sortField {String}
   * @param [order] {String}
   */
  sortList(tableId, sortField, order) {
    AppDispatcher.dispatch({
      actionType: TableConstants.SORT_TABLE,
      tableId,
      sortField,
      order
    });
  },

  /**
   * Edit specific field
   * @param tableId {String}
   * @param entityId {String}
   * @param fieldName {String}
   * @param stateFlag {Boolean} - edit start/end
   */
  editField(tableId, entityId, fieldName, stateFlag) {
    AppDispatcher.dispatch({
      actionType: TableConstants.EDIT_FIELD,
      tableId,
      entityId,
      fieldName,
      stateFlag
    });
  },

  /**
   * Emit key event for table
   * @param entityId {String}
   * @param event {Event}
   */
  emitKeyEvent(entityId, event) {
    AppDispatcher.dispatch({
      actionType: TableConstants.EMIT_KEY,
      entityId,
      event
    });
  },

  /**
   * Set lock for table
   * @param tableId {String}
   */
  setTableLock(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.SET_TABLE_LOCK,
      tableId
    });
  },

  /**
   * Release lock of table
   * @param tableId {String}
   */
  releaseTableLock(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.RELEASE_TABLE_LOCK,
      tableId
    });
  },

  /**
   * Add entity to table (e.g. uploading entity)
   * @param tableId {String}
   * @param entityData {Object}
   * @param entityData.name {String}
   * @param entityData.id {String}
   * @param entityData.type {String}
   */
  addEntity(tableId, entityData) {
    AppDispatcher.dispatch({
      actionType: TableConstants.ADD_ENTITY,
      tableId,
      entityData
    });
  },

  /**
   * Modifies entity data (all existing properties in entity with
   * entityId will be replaced with newEntityData).
   * To remove property pass omitProperties array of properties'
   *  names (e.g. ["proccess","percentage"])
   * @param tableId {String}
   * @param entityId {String}
   * @param newEntityData {Object}
   * @param [omitProperties] {Array}
   * @param [update] {Boolean} - if false - update won't be triggered.
   * If true - update will be triggered immediately, if not defined - update will be added to stack
   */
  modifyEntity(tableId, entityId, newEntityData, omitProperties, update) {
    AppDispatcher.dispatch({
      actionType: TableConstants.MODIFY_ENTITY,
      tableId,
      entityId,
      newEntityData,
      omitProperties,
      update
    });
  },

  /**
   * Removes entity with specific entityId
   * @param tableId {String}
   * @param entityId {String}
   */
  deleteEntity(tableId, entityId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.DELETE_ENTITY,
      tableId,
      entityId
    });
  },

  /**
   * Set table as an active
   * @param tableId {String}
   */
  setFocusOnTable(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.FOCUS_TABLE,
      tableId
    });
  },

  /**
   * Unset an active table
   * @param tableId {String}
   */
  removeFocusFromTable(tableId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.BLUR_TABLE,
      tableId
    });
  },

  recalculateTableDimensions() {
    AppDispatcher.dispatch({
      actionType: TableConstants.RECALCULATE_DIMENSIONS
    });
  },

  startProcess(id, type, proccessId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.PROCESS_START,
      id,
      type,
      proccessId
    });
  },

  endProcess(id, type, proccessId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.PROCESS_END,
      id,
      type,
      proccessId
    });
  },

  stepProcess(id, type, value, proccessId) {
    AppDispatcher.dispatch({
      actionType: TableConstants.PROCESS_STEP,
      id,
      type,
      value,
      proccessId
    });
  }
};

export default TableActions;
