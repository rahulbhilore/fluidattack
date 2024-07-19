import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";

const useStyles = makeStyles(theme => ({
  root: {
    padding: theme.spacing(2),
    borderBottom: "solid 1px rgba(100, 100, 100, 0.9)",
    margin: 0
  }
}));

export default function Block({ children }) {
  const classes = useStyles();
  return (
    <Grid container spacing={0} className={classes.root}>
      {children}
    </Grid>
  );
}

Block.propTypes = {
  children: PropTypes.node.isRequired
};
