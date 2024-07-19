import React, { PureComponent } from "react";
import { Shortcuts } from "react-shortcuts";
import { FormattedMessage } from "react-intl";
import Immutable from "immutable";
import PropTypes from "prop-types";
import {
  AutoSizer,
  Column,
  SortDirection,
  SortIndicator,
  InfiniteLoader
} from "react-virtualized";
import $ from "jquery";
import _ from "underscore";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationStore from "../../stores/ApplicationStore";
import SmartTableRow from "./SmartTableRow";
import SmartTableActions from "../../actions/SmartTableActions";
import SmartTableStore, {
  DIMENSIONS_EVENT,
  SELECT_EVENT,
  SCROLL_TO_ID_EVENT,
  SCROLL_TO_TOP_EVENT
} from "../../stores/SmartTableStore";
import ModalStore from "../../stores/ModalStore";
import ContextMenuStore from "../../stores/ContextMenuStore";
import MuiTable from "./MuiTable";
import Loader from "../Loader";
import "react-virtualized/styles.css";
import animateScroll from "./scrollAnimation";
import NoFileFoundSVG from "../../assets/images/NoFileFound.svg";

const STANDARD_MODAL_HEIGHT = 220;
const USE_ANIMATION = false;

export default class SmartTable extends PureComponent {
  static _calculateColumnWidth(column, totalWidth, columnsAmount) {
    if (column.width) {
      if (_.isString(column.width)) {
        return column.width;
      }
      if (_.isNumber(column.width)) {
        if (column.width < 1 && column.width > 0) {
          return totalWidth * column.width;
        }
        if (column.width === 1) {
          return totalWidth;
        }
        return column.width;
      }
      throw new Error("Incorrect column width (expected Number or String)");
    } else {
      return totalWidth / columnsAmount;
    }
  }

  static _getDatum(list, index) {
    return list.get(index % list.size);
  }

  static propTypes = {
    data: PropTypes.instanceOf(Immutable.List).isRequired,
    columns: PropTypes.instanceOf(Immutable.List).isRequired,
    isLoading: PropTypes.bool,
    rowHeight: PropTypes.number,
    widthPadding: PropTypes.number,
    tableType: PropTypes.string,
    presentation: PropTypes.instanceOf(Immutable.Map),
    handleDelete: PropTypes.func,
    handleRename: PropTypes.func,
    heightOffsets: PropTypes.number,
    customSorts: PropTypes.objectOf(PropTypes.func),
    noDataCaption: PropTypes.string,
    isModal: PropTypes.bool,
    defaultSortField: PropTypes.string,
    sortDirection: PropTypes.string,
    beforeSort: PropTypes.func,
    isRowLoaded: PropTypes.func,
    loadMoreRows: PropTypes.func,
    isLazy: PropTypes.bool,
    classes: PropTypes.shape({
      box: PropTypes.string,
      table: PropTypes.string
    }),
    eventHandlers: PropTypes.shape({
      row: PropTypes.shape({
        onDoubleClick: PropTypes.func
      })
    }),
    setTableDimensions: PropTypes.func,
    keyField: PropTypes.string
  };

  static defaultProps = {
    isLoading: false,
    tableType: "default",
    rowHeight: 65,
    widthPadding: 0,
    presentation: null,
    heightOffsets: 0,
    customSorts: {},
    handleDelete: () => null,
    handleRename: () => null,
    noDataCaption: "noData",
    isModal: false,
    defaultSortField: "",
    sortDirection: SortDirection.ASC,
    beforeSort: () => null,
    isRowLoaded: () => true,
    loadMoreRows: () => false,
    isLazy: false,
    classes: { box: "", table: "" },
    eventHandlers: {
      row: {
        onDoubleClick: () => null
      }
    },
    setTableDimensions: null,
    keyField: "_id"
  };

  constructor(props, context) {
    super(props, context);
    const sortBy =
      props.defaultSortField.length > 0
        ? props.defaultSortField
        : props.columns.get(0).dataKey;
    const tableId = MainFunctions.guid();
    this.state = {
      tableId,
      sortBy,
      sortDirection: props.sortDirection,
      tableHeight: 100,
      selectedRows: [],
      scrollTop: 0
    };
  }

  componentDidMount() {
    const { tableType } = this.props;
    const { tableId } = this.state;
    this.setTableDimensions();
    SmartTableActions.registerTable(tableId, tableType);
    SmartTableStore.addListener(SELECT_EVENT + tableId, this._onSelect);
    SmartTableStore.addListener(SCROLL_TO_ID_EVENT + tableId, this.scrollToId);
    SmartTableStore.addListener(
      SCROLL_TO_TOP_EVENT + tableId,
      this.scrollToTop
    );
    SmartTableStore.addListener(
      DIMENSIONS_EVENT + tableId,
      this.setTableDimensions
    );
    window.addEventListener("resize", this.resizeHandler);
  }

  componentWillUnmount() {
    const { tableId } = this.state;
    SmartTableActions.unregisterTable(tableId);
    SmartTableStore.removeListener(SELECT_EVENT + tableId, this._onSelect);
    SmartTableStore.removeListener(
      SCROLL_TO_ID_EVENT + tableId,
      this.scrollToId
    );
    SmartTableStore.removeListener(
      SCROLL_TO_TOP_EVENT + tableId,
      this.scrollToTop
    );
    SmartTableStore.removeListener(
      DIMENSIONS_EVENT + tableId,
      this.setTableDimensions
    );
    window.removeEventListener("resize", this.resizeHandler);
  }

  // eslint-disable-next-line react/no-unused-class-component-methods
  getTableId() {
    const { tableId } = this.state;
    return tableId;
  }

  handleScroll = ({ scrollTop }) => {
    this.setState({ scrollTop });
  };

  scrollToTop = () => {
    this.setState({ scrollTop: 0 });
  };

  scrollToId = objectId => {
    const { keyField } = this.props;
    const { sortBy, sortDirection, scrollTop } = this.state;
    const sortedList = this._sortList({ sortBy, sortDirection });
    const foundIndex = sortedList.findIndex(
      row => row.get(keyField) === objectId
    );
    if (foundIndex > -1) {
      const offset = this.tableElement.getOffsetForRow({
        alignment: "start",
        index: foundIndex
      });
      const onComplete = () => {
        document.dispatchEvent(
          new CustomEvent("HIGHLIGHT_ROW", { detail: { objectId } })
        );
      };
      if (USE_ANIMATION) {
        animateScroll({
          fromValue: scrollTop,
          toValue: offset,
          onUpdate: (newScrollTop, callback) =>
            this.setState({ scrollTop: newScrollTop }, callback),
          onComplete
        });
      } else {
        this.setState({ scrollTop: offset }, onComplete);
      }
    } else {
      // eslint-disable-next-line no-console
      console.warn(
        `No element to scroll to was found. Index: ${foundIndex} ID: ${objectId}`
      );
    }
  };

  resizeHandler = () => {
    this.setTableDimensions();
  };

  setTableDimensions = () => {
    const { isModal, setTableDimensions } = this.props;

    if (setTableDimensions)
      return this.setState({
        tableHeight: setTableDimensions()
      });

    let footerHeight =
      document.getElementsByTagName("footer")[0]?.offsetHeight ?? 0;
    if (
      MainFunctions.detectPageType() === "files" ||
      MainFunctions.detectPageType().includes("trash") ||
      location.pathname.indexOf(`/resources/`) !== -1
    ) {
      if (
        !ApplicationStore.getApplicationSetting("customization")
          .showFooterOnFileLoaderPage
      ) {
        footerHeight = 0;
      }
    }

    const height = isModal
      ? STANDARD_MODAL_HEIGHT
      : document.documentElement.clientHeight;

    const table = $(".ReactVirtualized__Table");

    const offset = table.offset();

    const { heightOffsets } = this.props;

    const totalOffsets = heightOffsets || offset.top + footerHeight;

    if (typeof table === "undefined" || typeof offset === "undefined")
      return null;

    return this.setState({
      tableHeight: isModal ? height : height - totalOffsets
    });
  };

  getGetter = id => {
    const { data, keyField } = this.props;
    return (
      data.find(item => item.get(keyField) === id) || new Immutable.Map()
    ).toJS();
  };

  _sort = ({ sortBy, sortDirection }) => {
    const { beforeSort } = this.props;

    beforeSort(sortDirection, sortBy);
    this.setState({ sortBy, sortDirection });
  };

  _headerRenderer = ({ dataKey, sortBy, sortDirection }) => {
    const { columns } = this.props;
    return (
      <div>
        <FormattedMessage
          id={columns.find(val => val.dataKey === dataKey).label}
        />
        {sortBy === dataKey && <SortIndicator sortDirection={sortDirection} />}
      </div>
    );
  };

  _handleShiftSelection = rowId => {
    const { keyField } = this.props;
    const { sortBy, sortDirection, tableId } = this.state;
    const sortedList = this._sortList({ sortBy, sortDirection });
    const alreadySelectedRows =
      SmartTableStore.getTableInfo(tableId).selectedRows || [];
    let selectedIndexes = alreadySelectedRows.map(selectedRowId =>
      sortedList.findIndex(item => item.get(keyField) === selectedRowId)
    );
    const requestedIndex = sortedList.findIndex(
      item => item.get(keyField) === rowId
    );
    selectedIndexes = selectedIndexes.concat(requestedIndex);
    const endElement = _.max(selectedIndexes);
    const startElement = _.min(selectedIndexes);
    const selectedIds = sortedList
      .slice(startElement, endElement + 1)
      .map(row => row.get(keyField))
      .toArray();

    const isAlreadySelected = alreadySelectedRows.includes(rowId);

    if (isAlreadySelected) {
      const filteredSelectedIds = selectedIds.filter(id => id !== rowId);
      SmartTableActions.selectRows(tableId, filteredSelectedIds);
    } else {
      SmartTableActions.selectRows(tableId, selectedIds);
    }
  };

  _renderRow = props => {
    const { rowData, key, isScrolling } = props;
    const { selectedRows, tableId } = this.state;
    const { columns, tableType, presentation, eventHandlers, keyField } =
      this.props;
    return (
      <SmartTableRow
        // eslint-disable-next-line react/jsx-props-no-spreading
        {...props}
        eventHandlers={eventHandlers.row}
        isSelected={selectedRows.indexOf(rowData.get(keyField)) > -1}
        tableId={tableId}
        key={`${key}_${rowData.get(keyField)}`}
        keyField={keyField}
        columns={columns}
        tableType={tableType}
        infoGetter={this.getGetter}
        handleShiftSelection={this._handleShiftSelection}
        presentation={presentation}
        isScrolling={isScrolling}
      />
    );
  };

  _onSelect = () => {
    const { tableId } = this.state;
    this.setState({
      selectedRows: SmartTableStore.getTableInfo(tableId).selectedRows || []
    });
  };

  _noRowsRenderer = () => {
    const { isLoading, noDataCaption } = this.props;
    if (isLoading === true) {
      return <Loader />;
    }
    return (
      <div className="noDataRow">
        <img
          src={NoFileFoundSVG}
          style={{ width: "60px" }}
          alt={noDataCaption}
        />
        <div style={{ marginTop: 8 }}>
          <FormattedMessage id={noDataCaption} />
        </div>
      </div>
    );
  };

  _onKeyEvent = (action, event) => {
    const focusedInputs = $("input:focus");
    if (
      focusedInputs.length === 0 &&
      ((ModalStore.isDialogOpen() === false &&
        ContextMenuStore.getCurrentInfo().isVisible === false) ||
        action === "DESELECT")
    ) {
      const { tableId } = this.state;
      const { data, handleDelete, handleRename, keyField } = this.props;
      const selectedRows = SmartTableStore.getSelectedRows(tableId);
      const { sortBy, sortDirection } = this.state;
      const sortedList = this._sortList({ sortBy, sortDirection });
      switch (action) {
        case "SELECT_UP":
        case "SELECT_DOWN": {
          event.preventDefault();
          event.stopPropagation();
          // Arrow Down || Up
          const selectedIndexes = selectedRows
            .map(selectedRowId =>
              sortedList.findIndex(item => item.get(keyField) === selectedRowId)
            )
            .filter(v => v !== -1);
          let rowIndexToSelect = 0;
          if (selectedIndexes.length) {
            const endElement = _.max(selectedIndexes);
            const startElement = _.min(selectedIndexes);
            if (action === "SELECT_UP") {
              rowIndexToSelect = startElement - 1;
              if (rowIndexToSelect < 0) {
                rowIndexToSelect = 0;
              }
            } else {
              rowIndexToSelect = endElement + 1;
              if (rowIndexToSelect > sortedList.size - 1) {
                rowIndexToSelect = sortedList.size - 1;
              }
            }
          }
          if (sortedList.has(rowIndexToSelect)) {
            // change selection only if needed
            if (
              selectedIndexes.length > 1 ||
              !selectedIndexes.includes(rowIndexToSelect)
            ) {
              SmartTableActions.selectRows(tableId, []);
              SmartTableActions.selectRows(tableId, [
                sortedList.get(rowIndexToSelect).get(keyField)
              ]);
            }

            // tableHeight - height of the table
            // scrollTop - where it's scrolled now (offset from top)
            const { tableHeight, scrollTop } = this.state;
            // single row height (default 65)
            const { rowHeight = 65 } = this.props;
            const offset = rowIndexToSelect * rowHeight;

            const numberOfRowsOnPage = Math.floor(tableHeight / rowHeight);
            if (action === "SELECT_DOWN") {
              // top of penultimate element
              const lastVisibleElementOffset =
                (numberOfRowsOnPage - 1) * rowHeight;
              const maxHeight =
                (sortedList.size - numberOfRowsOnPage) * rowHeight;
              if (
                offset > lastVisibleElementOffset &&
                // to make sure scroll isn't further than the table
                // https://graebert.atlassian.net/browse/XENON-66007
                scrollTop + rowHeight <= maxHeight
              ) {
                this.setState({ scrollTop: scrollTop + rowHeight });
              }
            } else if (action === "SELECT_UP") {
              // top of second element
              const firstVisibleElementOffset = scrollTop + rowHeight;

              if (offset < firstVisibleElementOffset) {
                this.setState({ scrollTop: offset });
              }
            }
          }
          break;
        }
        case "DESELECT":
          // ESC
          if (selectedRows.length > 0) {
            SmartTableActions.selectRows(tableId, []);
          }
          break;
        // TODO
        case "SELECT_ALL": {
          // ctrl+A
          event.preventDefault();
          const selectedIds = data.map(row => row.get(keyField)).toArray();
          SmartTableActions.selectRows(tableId, selectedIds);
          break;
        }
        // TODO
        case "DELETE":
          // delete
          if (selectedRows.length > 0) {
            const selectedItemsInfo = sortedList.filter(o =>
              selectedRows.includes(o.get(keyField))
            );
            const canBeDeleted = selectedItemsInfo.every(
              entity =>
                entity.has("permissions") &&
                entity.get("permissions").has("canDelete") &&
                entity.get("permissions").get("canDelete") === true
            );
            if (canBeDeleted) {
              handleDelete(selectedRows);
            }
          }
          break;
        case "RENAME": {
          if (selectedRows.length > 1) return;

          const selectedRow = selectedRows[0];
          handleRename(selectedRow);
          break;
        }
        case "OPEN": {
          if (selectedRows.length > 1) return;

          const selectedRow = selectedRows[0];

          SmartTableActions.openRow(tableId, selectedRow);
          break;
        }
        default:
          break;
      }
    }
  };

  _sortList = ({ sortBy, sortDirection }) => {
    const { data, customSorts } = this.props;
    let sortedData;
    if (customSorts[sortBy]) {
      sortedData = data.sort(customSorts[sortBy]);
    } else {
      sortedData = data.sort(item => item.get(sortBy));
    }
    return sortedData.update(dataArray =>
      sortDirection === SortDirection.DESC ? dataArray.reverse() : dataArray
    );
  };

  rowGetter = ({ index }) => {
    const { sortBy, sortDirection } = this.state;
    const sortedList = this._sortList({ sortBy, sortDirection });
    return SmartTable._getDatum(sortedList, index);
  };

  render() {
    const { sortBy, sortDirection, tableHeight, scrollTop } = this.state;
    const {
      tableType,
      columns,
      rowHeight,
      widthPadding,
      isRowLoaded,
      loadMoreRows,
      isLazy,
      isLoading,
      classes
    } = this.props;
    const sortedList = this._sortList({ sortBy, sortDirection });
    return (
      <InfiniteLoader
        isRowLoaded={isRowLoaded}
        loadMoreRows={loadMoreRows}
        rowCount={Infinity}
      >
        {({ onRowsRendered, registerChild }) => (
          <AutoSizer disableHeight>
            {({ width }) => (
              <Shortcuts
                name="TABLE"
                handler={this._onKeyEvent}
                targetNodeSelector="body"
                global
              >
                <MuiTable
                  inputRef={tableElement => {
                    this.tableElement = tableElement;
                    registerChild(tableElement);
                  }}
                  onRowsRendered={onRowsRendered}
                  rowGetter={this.rowGetter}
                  rowHeight={rowHeight || 65}
                  rowCount={sortedList.size}
                  width={width - widthPadding}
                  height={tableHeight}
                  headerHeight={30}
                  noRowsRenderer={this._noRowsRenderer}
                  overscanRowCount={0}
                  sort={this._sort}
                  sortBy={sortBy}
                  sortDirection={sortDirection}
                  rowRenderer={this._renderRow}
                  tableType={tableType}
                  isLazy={isLazy}
                  isLoading={isLoading}
                  classes={classes}
                  scrollTop={scrollTop}
                  onScroll={this.handleScroll}
                >
                  {columns.map(column => (
                    <Column
                      key={column.dataKey}
                      dataKey={column.dataKey}
                      headerRenderer={this._headerRenderer}
                      width={SmartTable._calculateColumnWidth(
                        column,
                        width - widthPadding,
                        columns.length
                      )}
                    />
                  ))}
                </MuiTable>
              </Shortcuts>
            )}
          </AutoSizer>
        )}
      </InfiniteLoader>
    );
  }
}
