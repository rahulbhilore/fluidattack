import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { makeStyles } from "@material-ui/core/styles";
import Box from "@material-ui/core/Box";

const useStyles = makeStyles(() => ({
  root: {
    padding: "0 10px 0 88px",
    width: "100%",
    display: "inline-block"
  },
  accessible: {
    "& span:hover": {
      textDecoration: "underline"
    }
  },
  xref: {
    padding: "0 10px 0 45px"
  }
}));

export default function NameBlock({ accessible, children, type }) {
  const classes = useStyles();
  return (
    <Box
      className={clsx(
        classes.root,
        accessible ? classes.accessible : null,
        classes[type]
      )}
    >
      {children}
    </Box>
  );
}

NameBlock.propTypes = {
  accessible: PropTypes.bool.isRequired,
  children: PropTypes.node.isRequired,
  type: PropTypes.string
};

NameBlock.defaultProps = {
  type: "files"
};
