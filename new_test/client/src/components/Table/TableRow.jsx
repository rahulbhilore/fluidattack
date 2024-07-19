/* eslint-disable global-require */
import React, { Component } from "react";
import propTypes from "prop-types";
import $ from "jquery";
import _ from "underscore";
import UserInfoStore from "../../stores/UserInfoStore";
import ContextMenuActions from "../../actions/ContextMenuActions";
import FilesListActions from "../../actions/FilesListActions";
import TableActions from "../../actions/TableActions";
import FilesListStore from "../../stores/FilesListStore";
import TableStore, {
  PROCESS_EVENT,
  CHANGE_EVENT
} from "../../stores/TableStore";
import MainFunctions from "../../libraries/MainFunctions";
import { iOSLongTapTime } from "../../constants/appConstants/AppTimingConstants";

import Name from "./tables/files/Name";
import Access from "./tables/files/Access";
import Modified from "./tables/files/Modified";
import Size from "./tables/files/Size";

import TemplatesName from "./tables/templates/Name";

const presentations = {
  files: {
    name: Name,
    access: Access,
    modified: Modified,
    size: Size
  },
  templates: {
    name: TemplatesName
  },
  xref: {
    name: Name,
    modified: Modified,
    size: Size
  }
};

export default class TableRow extends Component {
  static propTypes = {
    element: propTypes.shape({
      id: propTypes.string.isRequired,
      process: propTypes.string,
      nonBlockingProcess: propTypes.bool,
      mimeType: propTypes.string,
      type: propTypes.string.isRequired,
      enabled: propTypes.bool,
      deleted: propTypes.bool,
      recentAction: propTypes.string,
      updateDate: propTypes.number
    }).isRequired,
    tableId: propTypes.string.isRequired,
    updateHash: propTypes.number,
    isSelected: propTypes.bool
  };

  static defaultProps = {
    isSelected: false,
    updateHash: 0
  };

  constructor(props) {
    super(props);
    this.state = {
      touchStartTime: null,
      touchEventInfo: null,
      processes: {}
    };
  }

  componentDidMount() {
    const { tableId } = this.props;
    // for search
    if (
      (TableStore.getTable(tableId) || {}).type === "files" &&
      MainFunctions.detectPageType().includes("search")
    ) {
      TableStore.addListener(CHANGE_EVENT, this.recalculateVisibility);
    }

    const { element } = this.props;
    TableStore.addListener(PROCESS_EVENT + element.id, this.onProcess);
  }

  shouldComponentUpdate(
    { updateHash: newUpdateHash, isSelected: newSelected, element: newElement },
    { processes: newProcesses }
  ) {
    const { updateHash, isSelected, element } = this.props;
    const { processes } = this.state;
    return (
      isSelected !== newSelected ||
      updateHash !== newUpdateHash ||
      JSON.stringify(processes) !== JSON.stringify(newProcesses) ||
      element.updateDate !== newElement.updateDate
    );
  }

  componentWillUnmount() {
    // remove the listener anyway - it won't hurt
    TableStore.removeListener(CHANGE_EVENT, this.recalculateVisibility);
    const { element } = this.props;
    TableStore.removeListener(PROCESS_EVENT + element.id, this.onProcess);
  }

  /**
   * Selecting rows
   * @param e {Event}
   */
  handleClick = e => {
    const { tableId, element } = this.props;
    if (TableStore.getFocusedTable() !== tableId) {
      TableActions.setFocusOnTable(tableId);
    }
    if (e.button === 0) {
      if (
        !$(e.target).is("button") &&
        !$(e.target).parent().is("li") &&
        !$(e.target).is("img") &&
        $("input:focus").length === 0
      ) {
        if (TableStore.getTable(tableId).multiSelect) {
          if (!e.ctrlKey) {
            if (e.shiftKey) {
              const selectedIds = _.pluck(
                TableStore.getSelection(tableId),
                "id"
              ).concat([element.id]);
              const startPoint = _.min(
                selectedIds,
                row => $(document.getElementById(row)).position().top
              );
              const endPoint = _.max(
                selectedIds,
                row => $(document.getElementById(row)).position().top
              );
              const allToBeSelected = $(document.getElementById(startPoint))
                .nextUntil(document.getElementById(endPoint))
                .addBack()
                .add(document.getElementById(endPoint))
                .toArray();
              const tableObjects = TableStore.getTable(tableId).results;
              const newSelection = allToBeSelected.map(selectedRow =>
                _.findWhere(tableObjects, { id: $(selectedRow).attr("id") })
              );
              TableActions.multiSelect(tableId, newSelection);
            } else {
              TableActions.selectObject(tableId, element);
            }
          } else if (e.ctrlKey) {
            TableActions.addToSelected(tableId, element, false);
          }
        } else {
          TableActions.selectObject(tableId, element);
        }
      }
    }
  };

  /**
   * Handling events passed from TableView
   * @param e
   */
  // eslint-disable-next-line react/no-unused-class-component-methods
  handleEvents = e => {
    const { tableId: currentTable, element } = this.props;
    const rowInfo = TableStore.getRowInfo(currentTable, element.id);
    const { actions } = rowInfo;
    const { id } = element;
    switch (e.keyCode) {
      case 13:
        e.preventDefault();
        if (
          !_.find(actions.edit, (propValue, propKey) => {
            if (propValue === true) {
              TableActions.editField(currentTable, id, propKey, false);
              return true;
            }
            return false;
          })
        ) {
          e.stopPropagation();
          const ext =
            rowInfo.type === "folder"
              ? "folder"
              : MainFunctions.getExtensionFromName(rowInfo.name);
          const isOpenAvailable =
            (!element.process || element.nonBlockingProcess) &&
            UserInfoStore.extensionSupported(ext, element.mimeType) &&
            FilesListStore.getCurrentState() !== "trash";
          if (
            TableStore.getTable(currentTable).type === "files" &&
            isOpenAvailable
          ) {
            FilesListStore.open(id);
          }
        }
        break;
      case 27:
        _.find(actions.edit, (propValue, propKey) => {
          if (propValue === true) {
            e.preventDefault();
            e.stopPropagation();
            TableActions.editField(currentTable, id, propKey, false);
            return true;
          }
          return false;
        });
        break;
      default:
    }
  };

  /**
   * Showing context menu
   * @param e { Event | { target:Node, x:Number, y:Number } }
   * @param [isTouchEvent] {boolean}
   */
  showMenu = (e, isTouchEvent) => {
    const { touchStartTime } = this.state;
    const { tableId, element } = this.props;
    if (touchStartTime !== null) {
      this.setState({ touchStartTime: null });
    }
    const entityInfo = TableStore.getRowInfo(tableId, element.id);
    if (!entityInfo) return true;
    if (!_.isEmpty(entityInfo.processes)) return true;
    if (!entityInfo.process || entityInfo.nonBlockingProcess) {
      if (!TableStore.isSelected(tableId, element.id)) {
        TableActions.selectObject(tableId, element);
      }
      let x = 0;
      let y = 0;
      let target = null;
      if (isTouchEvent === true) {
        // shift for (+10, +10) to prevent "auto-clicking" on first option in menu
        x = e.x + 10;
        y = e.y + 10;
        ({ target } = e);
      } else {
        const evt = e || window.event;
        evt.preventDefault();
        ({ target } = evt);
        x = evt.pageX + 5;
        y = evt.pageY + 5;
      }
      if (!$(target).is("input")) {
        ContextMenuActions.showMenu(x, y, element.id);
        return true;
      }
    }
    return false;
  };

  onTouchStart = event => {
    const touchEventInfo = {
      target: event.target,
      x: event.touches[0].pageX,
      y: event.touches[0].pageY
    };
    const touchStartTime = Date.now();
    this.setState({
      touchStartTime,
      touchEventInfo
    });
  };

  onTouchEnd = event => {
    const touchStopTime = Date.now();
    const releaseXPosition = event.changedTouches[0].pageX;
    const releaseYPosition = event.changedTouches[0].pageY;
    const { touchStartTime, touchEventInfo } = this.state;
    if (
      touchStartTime !== null &&
      touchStopTime - touchStartTime > iOSLongTapTime &&
      Math.abs(releaseXPosition - touchEventInfo.x) < 10 &&
      Math.abs(releaseYPosition - touchEventInfo.y) < 10
    ) {
      this.showMenu(touchEventInfo, true);
    } else {
      this.setState({ touchStartTime: null, touchEventInfo: null });
    }
  };

  getRowClassNames = () => {
    const { isSelected, element } = this.props;
    const { processes } = this.state;
    const proccessNames = _.pluck(processes, "name") || [];
    const classNames = proccessNames;
    classNames.push(element.type);
    // if entity is selected - class should be applied for highlight
    if (isSelected) {
      classNames.push("selected");
    }
    // set disabled or enabled class
    if ((element.enabled || "").toString() !== "true") {
      classNames.push("disabled");
    } else {
      classNames.push("enabled");
    }
    classNames.push("tableRow");
    return classNames.join(" ");
  };

  handleDoubleClick = () => {
    const { tableId, element } = this.props;
    const tableType = TableStore.getTable(tableId).type;
    if (tableType === "files" || tableType === "xref") {
      const rowInfo = TableStore.getRowInfo(tableId, element.id);
      const ext =
        rowInfo.type === "folder"
          ? "folder"
          : MainFunctions.getExtensionFromName(rowInfo.name);
      const { storage, accountId } = FilesListStore.getCurrentFolder();
      if (rowInfo.type === "folder" && tableType === "xref") {
        FilesListStore.openXrefFolder(element.id);
      } else if (
        tableType !== "xref" &&
        (!rowInfo.process || rowInfo.nonBlockingProcess) &&
        UserInfoStore.extensionSupported(ext, rowInfo.mimeType) &&
        FilesListStore.getCurrentState() !== "trash" &&
        !TableStore.getRowInfo(tableId, element.id).actions.edit?.name
      ) {
        // we should open if there is no process or it's nonBlocking
        // extensionSupported
        // and this is not in trash
        FilesListStore.open(element.id, storage, accountId);
      }
    }
  };

  recalculateVisibility = () => {
    // used for search only!
    const { tableId, element } = this.props;
    const rowInfo = TableStore.getRowInfo(tableId, element.id);
    if (
      rowInfo &&
      !rowInfo.full &&
      TableStore.getTable(tableId).orderedBy !== null &&
      !element.id.includes("CS_") &&
      MainFunctions.inViewport(
        document.getElementById(element.id),
        MainFunctions.detectPageType().includes("search")
          ? document.getElementsByClassName("scrollable")[0]
          : document.getElementById(tableId)
      )
    ) {
      TableStore.removeListener(CHANGE_EVENT, this.recalculateVisibility);
      TableActions.modifyEntity(tableId, element.id, { full: true }, [], false);
      FilesListActions.updateEntityInfo(
        tableId,
        element.id,
        `${element.type}s`
      );
    }
  };

  onProcess = processData => {
    if (processData.name && processData.state) {
      const { processes: stateValue } = this.state;
      const processes = JSON.parse(JSON.stringify(stateValue));
      const doesProcessExist = Object.prototype.hasOwnProperty.call(
        processes,
        processData.name
      );
      switch (processData.state) {
        case "start":
        case "step":
          processes[processData.name] = _.extend(
            processes[processData.name] || {},
            processData
          );
          break;
        case "end":
          if (doesProcessExist) {
            delete processes[processData.name];
          }
          break;
        default:
          break;
      }
      this.setState({ processes });
    }
  };

  render() {
    const { tableId, element } = this.props;
    const { processes } = this.state;
    const table = TableStore.getTable(tableId);
    // remove recent action after 5 seconds (e.g. upload)
    if (element.recentAction === true) {
      setTimeout(() => {
        if (TableStore.isTableRegistered(tableId)) {
          TableActions.modifyEntity(tableId, element.id, {}, [
            "recentAction",
            "process",
            "percentage"
          ]);
        }
      }, 5000);
    }
    return (
      <tr
        key={element.id}
        className={this.getRowClassNames()}
        onMouseDown={this.handleClick}
        id={element.id}
        onContextMenu={this.showMenu}
        onTouchStart={this.onTouchStart}
        onTouchEnd={this.onTouchEnd}
        onDoubleClick={this.handleDoubleClick}
      >
        {Object.keys(table.fields).map((fieldName, i) => {
          const fieldCaption = table.fields[fieldName].caption || fieldName;
          return React.createElement(
            presentations[table.type][fieldCaption],
            _.extend(element, {
              processes,
              tableId,
              key: `TableRowField_${i}_${element.id}`,
              fieldName: fieldCaption,
              mode: TableStore.getRowInfo(tableId, element.id).actions.edit[
                fieldCaption
              ]
                ? "edit"
                : "view"
            })
          );
        })}
      </tr>
    );
  }
}
