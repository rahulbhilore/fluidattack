import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { makeStyles } from "@material-ui/core/styles";
import Box from "@material-ui/core/Box";

const XREF_TYPE = "xref";

const useStyles = makeStyles(() => ({
  root: {
    height: "68px",
    width: "68px",
    display: "inline-block",
    position: "absolute",
    top: "6px",
    bottom: "6px",
    "& img": {
      maxWidth: "100%",
      maxHeight: "100%",
      verticalAlign: "top",
      margin: "0 auto",
      display: "block"
    }
  },
  genericIcon: {
    height: "32px",
    width: "36px",
    top: "24px",
    bottom: "24px",
    left: "16px"
  },
  xref: {
    height: "36px",
    width: "36px",
    "& img": {
      maxWidth: "80%",
      maxHeight: "80%",
      margin: 0
    }
  }
}));

export default function IconBlock({ isGenericIcon, renderIcon, type }) {
  const classes = useStyles();

  return (
    <Box
      className={clsx(
        classes.root,
        isGenericIcon && type !== XREF_TYPE ? classes.genericIcon : null,
        classes[type]
      )}
    >
      {renderIcon}
    </Box>
  );
}

IconBlock.propTypes = {
  renderIcon: PropTypes.node,
  isGenericIcon: PropTypes.bool.isRequired,
  type: PropTypes.string
};

IconBlock.defaultProps = {
  renderIcon: null,
  type: null
};
