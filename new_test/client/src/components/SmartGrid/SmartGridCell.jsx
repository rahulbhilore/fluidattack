import React, { useState, useEffect, useContext } from "react";
import PropTypes from "prop-types";
import { Box } from "@material-ui/core";
import { createStyles, makeStyles } from "@material-ui/core/styles";
import SmartTableStore, { SELECT_EVENT } from "../../stores/SmartTableStore";
import ContextMenuActions from "../../actions/ContextMenuActions";
import SmartGridContext from "../../context/SmartGridContext";

const useStyles = makeStyles(theme =>
  createStyles({
    root: {
      // backgroundColor: theme.palette.LIGHT,
      color: theme.palette.DARK,
      width: countOfColumns => `${100 / countOfColumns}%`
    }
  })
);

// const LONG_PRESS_DELAY = 350;

export default function SmartGridCell({
  data,
  countOfColumns,
  gridItem,
  width,
  gridId,
  getGetter
}) {
  const classes = useStyles(countOfColumns);

  const { _id } = data;

  const [isSelected, setSelected] = useState(
    SmartTableStore.getSelectedRows(gridId).includes(_id)
  );
  // const [ isLongClick, setLongClick ] = useState(false);
  // const [ timeout, setStateTimeout ] = useState(null);
  // const [ blockTap, setBlockTap ] = useState(false);

  const { interactionsBlockDueScroll } = useContext(SmartGridContext);

  useEffect(() => {
    const onSelectedEvent = () => {
      const alreadySelectedCells = SmartTableStore.getSelectedRows(gridId);
      const isIs = alreadySelectedCells.includes(_id);

      setSelected(isIs);
    };
    SmartTableStore.addListener(SELECT_EVENT + gridId, onSelectedEvent);
    return () => {
      SmartTableStore.removeListener(SELECT_EVENT + gridId, onSelectedEvent);
    };
  }, [_id]);

  const touchStart = () => {
    // TODO: Touch event disabled for first release
    // start(e);
  };

  const touchEnd = () => {
    // clear(e);
  };

  // const start = () => {
  //   setStateTimeout(setTimeout(() => {
  //     const alreadySelectedCells = SmartTableStore.getSelectedRows(gridId);
  //     const x = ContextMenuStore.getCurrentInfo()
  //     console.log(x);
  //     setLongClick(true);
  //     console.log(["start", _id, isLongClick, blockTap]);
  //     if (!isSelected) {
  //       alreadySelectedCells.push(_id);
  //       SmartTableActions.selectRows(gridId, alreadySelectedCells);
  //       setSelected(true);
  //     } else {
  //       const alreadySelectedCells = SmartTableStore.getSelectedRows(gridId);
  //
  //       alreadySelectedCells.push(_id);
  //       SmartTableActions.deselectRows(gridId, _id);
  //       setSelected(false);
  //     }
  //   }, LONG_PRESS_DELAY));
  // }

  // const clear = () => {
  //   clearTimeout(timeout);
  //   console.log(["clear", _id, isLongClick]);
  //
  //   const isMenuVisible = ContextMenuStore.getCurrentInfo().isVisible;
  //
  //   if (isMenuVisible) return;
  //
  //   setTimeout(() => {
  //     setLongClick(false);
  //     if (isLongClick) return;
  //
  //     const selectedCells = SmartTableStore.getSelectedRows(gridId);
  //     SmartTableActions.deselectRows(gridId, selectedCells);
  //   }, 0);
  // }

  const showMenu = e => {
    // setBlockTap(true);
    if (interactionsBlockDueScroll) return;

    let selectedCells = SmartTableStore.getSelectedRows(gridId);
    if (selectedCells.length === 0) selectedCells = [_id];

    const coords = e.target.getBoundingClientRect();

    ContextMenuActions.showMenuForSelection(
      coords.x,
      coords.y,
      selectedCells,
      getGetter,
      "files"
    );
  };

  return (
    <Box
      className={classes.root}
      onTouchEnd={touchEnd}
      onMouseDown={touchStart}
      onTouchStart={touchStart}
      onMouseUp={touchEnd}
      onContextMenu={e => e.preventDefault()}
      data-component="smart-grid-cell"
      key={data._id}
    >
      {React.createElement(gridItem, {
        data,
        countOfColumns,
        width,
        selected: isSelected,
        showMenu,
        gridId
      })}
    </Box>
  );
}

SmartGridCell.propTypes = {
  data: PropTypes.shape({
    _id: PropTypes.string,
    name: PropTypes.string,
    updateDate: PropTypes.number,
    creationDate: PropTypes.number,
    thumbnail: PropTypes.string,
    type: PropTypes.string,
    mimeType: PropTypes.string,
    public: PropTypes.bool,
    isShared: PropTypes.bool,
    icon: PropTypes.string,
    permissions: PropTypes.shape({
      canViewPublicLink: PropTypes.bool
    }).isRequired
  }).isRequired,
  countOfColumns: PropTypes.number.isRequired,
  gridItem: PropTypes.oneOfType([PropTypes.object, PropTypes.node]).isRequired,
  width: PropTypes.number.isRequired,
  gridId: PropTypes.string.isRequired,
  getGetter: PropTypes.func.isRequired
};
