import React, { Component } from "react";
import $ from "jquery";
import PropTypes from "prop-types";
import Immutable from "immutable";
import {
  AutoSizer,
  CellMeasurerCache,
  InfiniteLoader,
  List,
  SortDirection
} from "react-virtualized";
import { FormattedMessage } from "react-intl";
import { Box, styled } from "@material-ui/core";
import LinearProgress from "@material-ui/core/LinearProgress";
import SmartGridRow from "./SmartGridRow";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationStore from "../../stores/ApplicationStore";
import SmartTableActions from "../../actions/SmartTableActions";
import SmartTableStore, {
  DIMENSIONS_EVENT,
  SELECT_EVENT
} from "../../stores/SmartTableStore";
import SmartGridContext from "../../context/SmartGridContext";
import Loader from "../Loader";
import NoFileFoundSVG from "../../assets/images/NoFileFound.svg";

const StyledBox = styled(Box)(({ theme }) => ({
  display: "inline-block",
  backgroundColor: theme.palette.GREY_BACKGROUND
}));

const StyledLinearProgress = styled(LinearProgress)(({ theme }) => ({
  height: "3px",
  "&.MuiLinearProgress-colorPrimary": {
    backgroundColor: theme.palette.GREY_TEXT
  },
  "& .MuiLinearProgress-bar1Indeterminate": {
    animation: "$cssload-width 4.5s cubic-bezier(0.45, 0, 1, 1) infinite",
    backgroundColor: theme.palette.KYLO
  },
  "& .MuiLinearProgress-bar2Indeterminate": {
    animation: "none"
  },
  "&.files": {
    position: "absolute",
    width: "100%",
    bottom: 0
  }
}));

const StyledNoDataBox = styled(Box)(({ theme }) => ({
  textAlign: "center",
  color: theme.palette.VADER,
  marginTop: theme.typography.pxToRem(14)
}));

export default class SmartGrid extends Component {
  static propTypes = {
    data: PropTypes.instanceOf(Immutable.List).isRequired,
    gridItem: PropTypes.oneOfType([PropTypes.object, PropTypes.node])
      .isRequired,
    fixedGridHeight: PropTypes.bool,
    isLoading: PropTypes.bool,
    gridType: PropTypes.string,
    countOfColumns: PropTypes.number,
    customSorts: PropTypes.objectOf(PropTypes.func),
    isLazy: PropTypes.bool,
    isRowLoaded: PropTypes.func,
    loadMoreRows: PropTypes.func,
    noDataCaption: PropTypes.string,
    setDimensions: PropTypes.func,
    heightOffsets: PropTypes.number
  };

  static defaultProps = {
    gridType: "default",
    fixedGridHeight: false,
    countOfColumns: 1,
    customSorts: {},
    isLoading: false,
    isLazy: false,
    isRowLoaded: () => true,
    loadMoreRows: () => false,
    noDataCaption: "noData",
    setDimensions: null,
    heightOffsets: 0
  };

  constructor(props) {
    super(props);
    /**
     *
     * @type {CellMeasurerCache}
     * @private
     */
    this._cache = new CellMeasurerCache({
      isLoading: false,
      defaultHeight: 50,
      fixedWidth: true
    });

    /**
     *
     * @type {List}
     * @private
     */
    this._listRef = null;
    this._listWidth = null;

    this._scrollTimer = null;

    const gridId = MainFunctions.guid();
    this.state = {
      gridId,
      sortedAndPreparedData: [],
      gridHeight: 300,
      interactionsBlockDueScroll: false
    };
  }

  componentDidMount() {
    const { gridType } = this.props;
    const { gridId } = this.state;
    this.setGridDimensions();
    SmartTableActions.registerTable(gridId, gridType);
    SmartTableStore.addListener(SELECT_EVENT + gridId, this._onSelect);
    SmartTableStore.addListener(
      DIMENSIONS_EVENT + gridId,
      this.setGridDimensions
    );
    this._sortList("name", SortDirection.ASC);
  }

  componentDidUpdate(prevProps) {
    const { data, countOfColumns } = this.props;
    const { data: prevData, countOfColumns: prevCountOfColumns } = prevProps;

    if (JSON.stringify(data) !== JSON.stringify(prevData)) {
      this._sortList("name", SortDirection.ASC);
    }

    if (countOfColumns !== prevCountOfColumns)
      this._sortList("name", SortDirection.ASC);
  }

  componentWillUnmount() {
    const { gridId } = this.state;
    SmartTableActions.unregisterTable(gridId);
    SmartTableStore.removeListener(SELECT_EVENT + gridId, this._onSelect);
    SmartTableStore.removeListener(
      DIMENSIONS_EVENT + gridId,
      this.setGridDimensions
    );
  }

  setGridDimensions = () => {
    const { setDimensions } = this.props;

    if (setDimensions) {
      this.setState({
        gridHeight: setDimensions(this._listRef)
      });
      return;
    }

    const body = $(window);

    let footerHeight =
      document.getElementsByTagName("footer")[0]?.offsetHeight ?? 0;
    if (MainFunctions.detectPageType() === "files") {
      if (
        !ApplicationStore.getApplicationSetting("customization")
          .showFooterOnFileLoaderPage
      ) {
        footerHeight = 0;
      }
    }

    const height = document.documentElement.clientHeight;

    const grid = $(".ReactVirtualized__List");

    const offset = grid.offset();

    const { heightOffsets } = this.props;

    const totalOffsets = heightOffsets || offset.top + footerHeight;

    if (typeof grid !== "undefined" && typeof offset !== "undefined") {
      this.setState({
        gridHeight: height - totalOffsets - 3
      });
    }

    $(window).resize(() => {
      if (grid && grid.offset) {
        const resizeOffset = grid.offset();
        const resizedHeight = body.height();
        const resizedTotalOffsets =
          heightOffsets || resizeOffset.top + footerHeight;
        this.setState({
          gridHeight: resizedHeight - resizedTotalOffsets - 3
        });
      }
    });
  };

  _onSelect = () => {
    this.forceUpdate();
  };

  _sortList = (sortBy, sortDirection) => {
    const { data, customSorts } = this.props;

    let sortedAndPreparedData;
    if (customSorts[sortBy]) {
      sortedAndPreparedData = data.sort(customSorts[sortBy]);
    } else {
      sortedAndPreparedData = data.sort(item => item.get(sortBy));
    }
    sortedAndPreparedData.update(dataArray =>
      sortDirection === SortDirection.DESC ? dataArray.reverse() : dataArray
    );

    this.setState({
      sortedAndPreparedData: this._prepareDataForGrid(sortedAndPreparedData)
    });

    this._cache.clearAll();
    this._listRef.forceUpdateGrid();
  };

  _prepareDataForGrid = data => {
    function isRowDataTheSameTypeEntities(rowData) {
      if (!rowData.length) return true;

      if (rowData.length === 1) return true;

      let isTheSame = true;
      const initialValueType = rowData[0].type;

      rowData.forEach(elem => {
        if (!isTheSame) return;

        if (elem.type !== initialValueType) isTheSame = false;
      });

      return isTheSame;
    }

    const { countOfColumns } = this.props;

    const dataJs = data.toJS();

    const newDataArray = [];
    let rowData = [];

    dataJs.forEach(elem => {
      if (countOfColumns && !isRowDataTheSameTypeEntities(rowData)) {
        const tempData = rowData.pop();
        newDataArray.push(rowData);
        rowData = [tempData];
        rowData.push(elem);
        return;
      }

      if (rowData.length === countOfColumns) {
        newDataArray.push(rowData);
        rowData = [];
      }

      rowData.push(elem);
    });

    if (rowData.length === 0) return newDataArray;

    newDataArray.push(rowData);
    return newDataArray;
  };

  getGetter = id => {
    const { data } = this.props;
    return (
      data.find(item => item.get("_id") === id) || new Immutable.Map()
    ).toJS();
  };

  rowRenderer = ({
    key, // Unique key within array of rows
    index, // Index of row within collection
    style, // Style object to be applied to row (to position it),
    parent
  }) => {
    const { gridItem, countOfColumns } = this.props;
    const { sortedAndPreparedData, gridId, interactionsBlockDueScroll } =
      this.state;

    if (!sortedAndPreparedData) return null;

    return (
      <SmartGridRow
        cache={this._cache}
        columnIndex={0}
        key={key}
        parent={parent}
        rowIndex={index}
        style={style}
        data={sortedAndPreparedData[index]}
        countOfColumns={countOfColumns}
        gridItem={gridItem}
        width={this._listWidth}
        gridId={gridId}
        getGetter={this.getGetter}
        interactionsBlockDueScroll={interactionsBlockDueScroll}
      />
    );
  };

  noRowsRenderer = () => {
    const { isLoading, noDataCaption } = this.props;
    if (isLoading === true) {
      return <Loader />;
    }

    return (
      <StyledNoDataBox className="noDataRow">
        <img
          src={NoFileFoundSVG}
          style={{ width: "60px" }}
          alt={noDataCaption}
        />
        <div style={{ marginTop: 8 }}>
          <FormattedMessage id={noDataCaption} />
        </div>
      </StyledNoDataBox>
    );
  };

  onResize = () => {
    const { fixedGridHeight } = this.props;
    if (!fixedGridHeight) this._cache.clearAll();

    this._listRef.forceUpdateGrid();
    this._cache.clearAll();
    this.forceUpdate(); // TODO: this is a temporary slow solution, need to do better on the end of developing
  };

  onScroll = () => {
    const { interactionsBlockDueScroll } = this.state;

    clearTimeout(this._scrollTimer);
    this._scrollTimer = setTimeout(() => {
      this.setState({
        interactionsBlockDueScroll: false
      });
    }, 300);

    if (interactionsBlockDueScroll) return;

    this.setState({
      interactionsBlockDueScroll: true
    });
  };

  render() {
    const { isLazy, isRowLoaded, loadMoreRows, isLoading } = this.props;
    const { sortedAndPreparedData, gridHeight, interactionsBlockDueScroll } =
      this.state;

    return (
      <SmartGridContext.Provider
        // DK: TODO: this is a temporary solution, need to do better
        // eslint-disable-next-line react/jsx-no-constructed-context-values
        value={{
          interactionsBlockDueScroll
        }}
      >
        <InfiniteLoader
          isRowLoaded={isRowLoaded}
          loadMoreRows={loadMoreRows}
          rowCount={Infinity}
        >
          {({ onRowsRendered, registerChild }) => (
            <AutoSizer disableHeight onResize={this.onResize}>
              {({ width }) => {
                this._listWidth = width;

                return (
                  <StyledBox>
                    <List
                      width={width}
                      height={gridHeight}
                      rowCount={sortedAndPreparedData.length}
                      rowHeight={this._cache.rowHeight}
                      rowRenderer={this.rowRenderer}
                      noRowsRenderer={this.noRowsRenderer}
                      ref={ref => {
                        this._listRef = ref;
                        registerChild(ref);
                      }}
                      onRowsRendered={onRowsRendered}
                      onScroll={this.onScroll}
                      isLoading={isLoading}
                    />
                    {isLazy ? (
                      <StyledLinearProgress variant="indeterminate" />
                    ) : null}
                  </StyledBox>
                );
              }}
            </AutoSizer>
          )}
        </InfiniteLoader>
      </SmartGridContext.Provider>
    );
  }
}
