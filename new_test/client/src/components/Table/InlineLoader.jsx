import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import makeStyles from "@material-ui/core/styles/makeStyles";

const useStyles = makeStyles(theme => ({
  root: {
    width: "100%",
    position: "absolute",
    height: ".3rem",
    bottom: 0
  },
  xref: {
    bottom: theme.typography.pxToRem(66)
  },
  progress: {
    backgroundColor: theme.palette.REY,
    height: ".25rem",
    position: "relative",
    width: "100%"
  },
  bar: {
    backgroundSize: "23rem .25rem",
    height: "100%",
    position: "relative",
    backgroundColor: theme.palette.KYLO,
    animation: "$load 4.5s cubic-bezier(0.45, 0, 1, 1) infinite"
  },
  "@keyframes load": {
    "0%, 100%": {
      transitionTimingFunction: "cubic-bezier(1, 0, 0.65, 0.85)"
    },
    "0%": {
      width: 0
    },
    "100%": {
      width: "100%"
    }
  }
}));

export default function InlineLoader({ xref }) {
  const classes = useStyles();
  return (
    <div className={xref ? clsx(classes.root, classes.xref) : classes.root}>
      <div className={classes.progress}>
        <div className={classes.bar} />
      </div>
    </div>
  );
}

InlineLoader.propTypes = {
  xref: PropTypes.bool
};

InlineLoader.defaultProps = {
  xref: false
};
