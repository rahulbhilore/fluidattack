import React, { useCallback, useEffect } from "react";
import PropTypes from "prop-types";
import Menu from "@mui/material/Menu";
// @ts-ignore
import { Shortcuts } from "react-shortcuts";
import { menuItemClasses } from "@mui/material";
import ContextMenuActions from "../../actions/ContextMenuActions";
import ContextMenuStore from "../../stores/ContextMenuStore";
import { CONTEXT_OFFSET } from "../../constants/ContextMenuConstants";

const shortcutsHandler = (action: string) => {
  const { isVisible } = ContextMenuStore.getCurrentInfo();

  if (!isVisible) return;

  const contextItems = Array.from(
    document.getElementsByClassName("contextItem")
  ) as Array<HTMLDivElement>;

  switch (action) {
    case "SELECT_UP": {
      let newRowId = ContextMenuStore.getCurrentInfo().selectedRow - 1;
      if (newRowId < 0) {
        newRowId = 0;
      }
      ContextMenuActions.selectRow(newRowId);
      break;
    }
    case "SELECT_DOWN": {
      let newRowId = ContextMenuStore.getCurrentInfo().selectedRow + 1;
      if (newRowId >= contextItems.length) {
        newRowId = contextItems.length - 1;
      }
      ContextMenuActions.selectRow(newRowId);
      break;
    }
    case "EXEC": {
      if (ContextMenuStore.getCurrentInfo().selectedRow > -1) {
        for (
          let i = 0;
          i < ContextMenuStore.getCurrentInfo().selectedRow;
          i += 1
        ) {
          if (i === ContextMenuStore.getCurrentInfo().selectedRow) {
            contextItems[i].click();
            break;
          }
        }
      }
      break;
    }
    default:
      break;
  }
};

type Props = {
  isVisible: boolean;
  top: number;
  left: number;
  children: React.ReactNode | Array<React.ReactNode>;
};

export default function KudoMenu({ isVisible, top, left, children }: Props) {
  useEffect(() => {
    function globalClickListener() {
      setTimeout(() => {
        if (!isVisible) return;
        ContextMenuActions.hideMenu();
      }, 0);
    }
    setTimeout(() => {
      document
        .getElementById("react")
        ?.addEventListener("click", globalClickListener);
    }, 0);
    return function cleanup() {
      document
        .getElementById("react")
        ?.removeEventListener("click", globalClickListener);
    };
  }, [isVisible]);

  const moveMenu = useCallback(
    (e: React.MouseEvent<HTMLDivElement, MouseEvent>) => {
      e.preventDefault();
      ContextMenuActions.hideMenu();
      // Should we move or close or propagate event?
      if (
        e.clientX + CONTEXT_OFFSET !== left ||
        e.clientY + CONTEXT_OFFSET !== top
      )
        ContextMenuActions.move(
          e.clientX + CONTEXT_OFFSET,
          e.clientY + CONTEXT_OFFSET
        );
    },
    []
  );
  const handleMouseMove = useCallback(() => {
    const contextItems = Array.from(
      document.getElementsByClassName("contextItem")
    ) as Array<HTMLDivElement>;
    contextItems.forEach(item => {
      item.classList.remove("active");
    });
  }, []);

  return (
    <Shortcuts
      name="CONTEXT"
      handler={shortcutsHandler}
      targetNodeSelector="body"
      global
      isolate
    >
      <Menu
        keepMounted
        open={isVisible}
        transitionDuration={0}
        onClose={ContextMenuActions.hideMenu}
        anchorReference="anchorPosition"
        sx={{
          pointerEvents: "none",
          zIndex: theme => `${theme.zIndex.modal + 1} !important`
        }}
        anchorPosition={
          top !== null && left !== null ? { top, left } : undefined
        }
        onContextMenu={moveMenu}
        onMouseMove={handleMouseMove}
        data-component="menu-block"
        slotProps={{
          paper: {
            className: "contextMenu",
            sx: {
              borderRadius: 0,
              backgroundColor: theme => theme.palette.SNOKE,
              pointerEvents: "all"
            }
          }
        }}
        MenuListProps={{
          sx: {
            padding: 0,
            [`& .${menuItemClasses.root}`]: {
              minHeight: 0
            }
          }
        }}
      >
        {children}
      </Menu>
    </Shortcuts>
  );
}

KudoMenu.propTypes = {
  isVisible: PropTypes.bool.isRequired,
  top: PropTypes.number.isRequired,
  left: PropTypes.number.isRequired,
  children: PropTypes.node.isRequired
};
