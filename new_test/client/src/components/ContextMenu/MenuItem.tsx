import React, { useCallback } from "react";
import PropTypes from "prop-types";
import MUIMenuItem from "@mui/material/MenuItem";
import { Typography, CircularProgress, Box } from "@mui/material";
import { FormattedMessage } from "react-intl";
import clsx from "clsx";
import MainFunctions from "../../libraries/MainFunctions";
import ContextMenuActions from "../../actions/ContextMenuActions";

type Props = {
  id: string;
  onClick: () => void;
  image: string;
  caption: string;
  className?: string;
  dataComponent?: string;
  isLoading?: boolean;
  autoClose?: boolean;
};

export default function MenuItem({
  id,
  onClick,
  image,
  caption,
  className,
  dataComponent,
  isLoading,
  autoClose
}: Props) {
  const functionOverlay = useCallback(() => {
    if (onClick) {
      // Hide should come first, because onClick might be "long" job
      onClick();
      if (autoClose) {
        ContextMenuActions.hideMenu();
      }
    }
  }, [onClick, autoClose]);
  const { onKeyDown } = MainFunctions.getA11yHandler(functionOverlay);

  return (
    <MUIMenuItem
      id={id}
      className={clsx(className, "contextItem", isLoading ? "loading" : null)}
      sx={{
        cursor: "pointer",
        margin: 0,
        padding: "0 20px",
        minWidth: 165,
        backgroundColor: theme => theme.palette.SNOKE,
        opacity: isLoading ? 0.6 : 1,
        "&:hover,&:focus,&.active": {
          // DK: For some bizzare reason - to do this without important
          // we need to use theme's override.
          // not sure if this is something we want to do or not
          backgroundColor: theme => `${theme.palette.OBI} !important;`
        },
        "&.loading": {
          pointerEvents: "none",
          color: theme => theme.palette.CLONE
        }
      }}
      onClick={functionOverlay}
      onKeyDown={onKeyDown}
      role="button"
      tabIndex={0}
      data-component={dataComponent}
    >
      <Box
        sx={{
          alignItems: "center",
          justifyContent: "center",
          display: "flex",
          width: "16px",
          height: "16px",
          marginRight: "10px"
        }}
      >
        {isLoading ? (
          <CircularProgress
            style={{
              color: "white",
              opacity: isLoading ? 1 : 0,
              transition: "all .3s"
            }}
            size="12px"
          />
        ) : (
          image && (
            <img
              src={image}
              alt={caption}
              style={{
                width: "16px",
                height: "16px"
              }}
            />
          )
        )}
      </Box>
      {caption && caption.length ? (
        <Typography
          sx={{
            color: theme => theme.palette.LIGHT,
            fontSize: 12,
            whiteSpace: "nowrap",
            lineHeight: "36px"
          }}
        >
          <FormattedMessage id={caption} />
        </Typography>
      ) : null}
    </MUIMenuItem>
  );
}

MenuItem.propTypes = {
  onClick: PropTypes.func.isRequired,
  image: PropTypes.string,
  caption: PropTypes.string.isRequired,
  className: PropTypes.string,
  id: PropTypes.string.isRequired,
  dataComponent: PropTypes.string,
  isLoading: PropTypes.bool,
  autoClose: PropTypes.bool
};

MenuItem.defaultProps = {
  className: "",
  image: null,
  dataComponent: "",
  isLoading: false,
  autoClose: true
};
