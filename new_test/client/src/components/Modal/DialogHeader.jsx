import React, { useState, useRef, useLayoutEffect } from "react";
import PropTypes from "prop-types";
import DialogTitle from "@material-ui/core/DialogTitle";
import IconButton from "@material-ui/core/IconButton";
import CloseIcon from "@material-ui/icons/Close";
import makeStyles from "@material-ui/core/styles/makeStyles";
import SmartTooltip from "../SmartTooltip/SmartTooltip";
import DialogIcon from "./DialogIcon";

const useStyles = makeStyles(theme => ({
  root: {
    margin: 0,
    padding: theme.spacing(2),
    backgroundColor: theme.palette.secondary.main
  },
  closeButton: {
    position: "absolute",
    right: theme.spacing(1),
    top: theme.spacing(1),
    padding: theme.spacing(1),
    "&:hover,&:focus,&:active": {
      background: "none"
    }
  },
  captionBlock: {
    textAlign: "left",
    whiteSpace: "nowrap",
    overflow: "hidden",
    width: "calc(100% - 25px)"
  },
  caption: {
    verticalAlign: "middle",
    [theme.breakpoints.down("sm")]: {
      whiteSpace: "break-spaces"
    }
  }
}));

export default function DialogHeader({
  caption,
  currentDialog,
  onClose,
  enforceTooltip,
  fullCaption,
  ...other
}) {
  const classes = useStyles();
  const [isTooltipToShow, setTooltip] = useState(false);
  const title = useRef();
  const text = useRef();
  useLayoutEffect(() => {
    if (
      isTooltipToShow === false &&
      (enforceTooltip ||
        (title.current &&
          text.current &&
          text.current.offsetWidth > title.current.offsetWidth))
    ) {
      setTooltip(true);
    }
  }, [title, text, isTooltipToShow, caption, enforceTooltip]);
  return (
    // eslint-disable-next-line react/jsx-props-no-spreading
    <DialogTitle disableTypography className={classes.root} {...other}>
      <SmartTooltip
        forcedOpen={isTooltipToShow}
        placement="bottom"
        title={fullCaption || caption}
      >
        <div ref={title} className={classes.captionBlock}>
          <DialogIcon dialogName={currentDialog} />
          <span ref={text} className={classes.caption}>
            {caption}
          </span>
        </div>
      </SmartTooltip>
      <IconButton
        aria-label="close"
        className={classes.closeButton}
        onClick={onClose}
      >
        <CloseIcon />
      </IconButton>
    </DialogTitle>
  );
}

DialogHeader.propTypes = {
  caption: PropTypes.node.isRequired,
  onClose: PropTypes.func.isRequired,
  currentDialog: PropTypes.string.isRequired,
  enforceTooltip: PropTypes.bool,
  fullCaption: PropTypes.string
};

DialogHeader.defaultProps = {
  enforceTooltip: false,
  fullCaption: ""
};
