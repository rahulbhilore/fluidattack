import React, { Component } from "react";
import PropTypes from "prop-types";
import { CellMeasurer, CellMeasurerCache } from "react-virtualized";
import { Box, styled } from "@material-ui/core";
import SmartGridCell from "./SmartGridCell";

const StyledBox = styled(Box)(() => ({
  display: "flex",
  alignItems: "flex-start"
}));

export default class SmartGridRow extends Component {
  static propTypes = {
    data: PropTypes.arrayOf(
      PropTypes.shape({ _id: PropTypes.string.isRequired })
    ).isRequired,
    gridItem: PropTypes.oneOfType([PropTypes.object, PropTypes.node])
      .isRequired,
    countOfColumns: PropTypes.number.isRequired,
    gridId: PropTypes.string.isRequired,
    getGetter: PropTypes.func.isRequired,
    width: PropTypes.number.isRequired,
    parent: PropTypes.oneOfType([PropTypes.object, PropTypes.node]).isRequired,
    rowIndex: PropTypes.number.isRequired,
    cache: PropTypes.instanceOf(CellMeasurerCache).isRequired,
    style: PropTypes.oneOfType([PropTypes.object, PropTypes.string]).isRequired
  };

  getGridElements = measure => {
    const { data, countOfColumns, gridItem, width, gridId, getGetter } =
      this.props;

    const cells = [];

    if (!data) return null;

    data.forEach(elem => {
      cells.push(
        <SmartGridCell
          data={elem}
          // key={elem._id}
          gridItem={gridItem}
          countOfColumns={countOfColumns}
          measure={measure}
          width={Math.ceil(width / countOfColumns)}
          gridId={gridId}
          getGetter={getGetter}
        />
      );
    });

    return cells;
  };

  render() {
    const { cache, parent, rowIndex, style } = this.props;

    return (
      <CellMeasurer
        cache={cache}
        columnIndex={0}
        parent={parent}
        rowIndex={rowIndex}
      >
        {({ measure, registerChild }) => (
          <Box
            style={style}
            ref={registerChild}
            data-component="smart-grid-row"
          >
            <StyledBox>{this.getGridElements(measure)}</StyledBox>
          </Box>
        )}
      </CellMeasurer>
    );
  }
}
