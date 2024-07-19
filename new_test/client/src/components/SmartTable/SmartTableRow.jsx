import React, { Component } from "react";
import PropTypes from "prop-types";
import Immutable from "immutable";
import _ from "underscore";
import $ from "jquery";
import clsx from "clsx";
import ContextMenuActions from "../../actions/ContextMenuActions";
import SmartTableActions from "../../actions/SmartTableActions";
import SmartTableStore from "../../stores/SmartTableStore";
import MainFunctions from "../../libraries/MainFunctions";

export default class SmartTableRow extends Component {
  static propTypes = {
    rowData: PropTypes.instanceOf(Immutable.Map).isRequired,
    columns: PropTypes.instanceOf(Immutable.List).isRequired,
    tableType: PropTypes.string,
    style: PropTypes.objectOf(
      PropTypes.oneOfType([PropTypes.string, PropTypes.number])
    ).isRequired,
    tableId: PropTypes.string.isRequired,
    isSelected: PropTypes.bool,
    isScrolling: PropTypes.bool,
    handleShiftSelection: PropTypes.func.isRequired,
    infoGetter: PropTypes.func,
    presentation: PropTypes.instanceOf(Immutable.Map),
    eventHandlers: PropTypes.shape({
      onDoubleClick: PropTypes.func
    }),
    keyField: PropTypes.string
  };

  static defaultProps = {
    tableType: "default",
    isSelected: false,
    isScrolling: false,
    infoGetter: () => undefined,
    presentation: null,
    eventHandlers: {
      onDoubleClick: () => null
    },
    keyField: "_id"
  };

  constructor(props) {
    super(props);
    this.state = {
      isHighlighted: false
    };
  }

  componentDidMount() {
    document.addEventListener("HIGHLIGHT_ROW", this.highlightRow);
  }

  shouldComponentUpdate(nextProps, nextState) {
    const { isHighlighted } = this.state;
    const { isHighlighted: newHighlight } = nextState;
    return (
      // JSON.stringify(this.props) !== JSON.stringify(nextProps) ||
      this.stringify(this.props) !== this.stringify(nextProps) ||
      isHighlighted !== newHighlight
    );
  }

  componentWillUnmount() {
    document.removeEventListener("HIGHLIGHT_ROW", this.highlightRow);
  }

  getColumnWidth(column) {
    const { style, columns } = this.props;
    const totalWidth = style.width;
    const columnsAmount = columns.size;
    if (column.width) {
      if (_.isString(column.width)) {
        return column.width;
      }
      if (_.isNumber(column.width)) {
        if (column.width < 1 && column.width > 0) {
          return totalWidth * column.width;
        }
        return column.width;
      }
      throw new Error("Incorrect column width (expected Number or String)");
    } else {
      return totalWidth / columnsAmount;
    }
  }

  /**
   * Showing context menu
   * @param e { Event | { target:Node, x:Number, y:Number } }
   * @param [isTouchEvent] {boolean}
   */
  showMenu = (e, isTouchEvent) => {
    if (MainFunctions.isSelection(e)) return;
    const {
      rowData: entityInfo,
      tableId,
      tableType,
      infoGetter,
      keyField
    } = this.props;
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
      x = evt.pageX;
      y = evt.pageY;
    }
    let selectedRows = SmartTableStore.getSelectedRows(tableId);
    if (selectedRows.indexOf(entityInfo.get(keyField)) === -1) {
      selectedRows = [entityInfo.get(keyField)];
      SmartTableActions.selectRows(tableId, selectedRows);
    }
    if (!$(target).is("input")) {
      ContextMenuActions.showMenuForSelection(
        x,
        y,
        selectedRows,
        infoGetter,
        tableType,
        tableId
      );
    }
  };

  selectRow = e => {
    if (e.target.tagName === "INPUT") return true;
    e.preventDefault();
    const { rowData, tableId, handleShiftSelection, keyField } = this.props;
    if (e.shiftKey === true) {
      handleShiftSelection(rowData.get(keyField));
    } else {
      let selectedRows = [rowData.get(keyField)];
      const alreadySelectedRows = SmartTableStore.getSelectedRows(tableId);
      if (e.ctrlKey === true) {
        const isAlreadySelected = alreadySelectedRows.includes(selectedRows[0]);
        if (isAlreadySelected) {
          selectedRows = alreadySelectedRows.filter(
            id => id !== selectedRows[0]
          );
        } else {
          selectedRows = selectedRows
            .filter(id => !alreadySelectedRows.includes(id))
            .concat(alreadySelectedRows);
        }
        const uniqueSelectedRows = [...new Set(selectedRows)];
        SmartTableActions.selectRows(tableId, uniqueSelectedRows);
      } else {
        SmartTableActions.selectRows(tableId, selectedRows);
      }
    }
    return false;
  };

  handleDoubleClick = event => {
    const { eventHandlers, rowData } = this.props;
    if (eventHandlers.onDoubleClick) {
      eventHandlers.onDoubleClick(event, rowData);
    }
  };

  highlightRow = ({ detail }) => {
    const { objectId } = detail;
    const { rowData, keyField } = this.props;
    const rowId = rowData.get(keyField);
    if (objectId === rowId) {
      this.setState({ isHighlighted: true }, () => {
        setTimeout(() => {
          this.setState({ isHighlighted: false });
        }, 3000);
      });
    }
  };

  /**
   * Custom stringify function to prevent circular references in new resources
   * @param {*} obj
   * @returns
   */
  /* eslint-disable-next-line class-methods-use-this */
  stringify(obj) {
    let cache = [];
    const str = JSON.stringify(obj, (key, value) => {
      if (typeof value === "object" && value !== null) {
        if (cache.indexOf(value) !== -1) {
          // Circular reference found, discard key
          return null;
        }
        // Store value in our collection
        cache.push(value);
      }
      return value;
    });
    cache = null; // reset the cache
    return str;
  }

  render() {
    const {
      presentation: tablePresentation,
      isSelected,
      style,
      rowData,
      columns,
      tableType,
      tableId,
      isScrolling,
      keyField
    } = this.props;
    const { isHighlighted } = this.state;
    const { onKeyDown } = MainFunctions.getA11yHandler(this.selectRow);
    return (
      <div
        className={clsx(
          "tableRow",
          "ReactVirtualized__Table__row",
          rowData.get("enabled") ? "" : "disabled",
          isSelected ? "selected" : "",
          isHighlighted ? "highlighted" : ""
        )}
        style={style}
        onContextMenu={this.showMenu}
        onClick={this.selectRow}
        role="row"
        tabIndex={0}
        onKeyDown={onKeyDown}
        onDoubleClick={this.handleDoubleClick}
        data-component={
          rowData.get("isRoot")
            ? "root_folder"
            : rowData.get("type") || "object"
        }
        data-name={rowData.get("name") || "unknown"}
      >
        {columns.map(column => {
          if (
            tableType === "default" ||
            !tablePresentation ||
            tablePresentation.get(column.dataKey) === null
          ) {
            return (
              <div
                key={`${column.dataKey}_${rowData.get(keyField)}`}
                className="tableColumn ReactVirtualized__Table__rowColumn"
                style={{
                  flex: `0 1 ${this.getColumnWidth(column)}px`,
                  overflow: "hidden"
                }}
              >
                {rowData.get(column.dataKey)}
              </div>
            );
          }
          if (tablePresentation && tablePresentation.get(column.dataKey)) {
            return (
              <div
                key={`${column.dataKey}_${rowData.get(keyField)}`}
                className="tableColumn ReactVirtualized__Table__rowColumn"
                style={{
                  flex: `0 1 ${this.getColumnWidth(column)}px`,
                  overflow: "hidden"
                }}
              >
                {React.createElement(tablePresentation.get(column.dataKey), {
                  data: rowData.toJS(),
                  getter: rowData.get.bind(rowData),
                  [column.dataKey]: rowData.get(column.dataKey),
                  _id: rowData.get(keyField),
                  tableId,
                  isScrolling
                })}
              </div>
            );
          }
          return null;
        })}
      </div>
    );
  }
}
