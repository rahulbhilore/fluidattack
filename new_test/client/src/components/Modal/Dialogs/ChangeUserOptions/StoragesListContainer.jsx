import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";

const useStyles = makeStyles(theme => ({
  root: {
    width: "100%",
    margin: 0,
    padding: theme.spacing(2)
  }
}));

export default function StoragesListContainer({ children }) {
  const classes = useStyles();
  return (
    <Grid container spacing={2} className={classes.root}>
      {children}
    </Grid>
  );
}

StoragesListContainer.propTypes = {
  children: PropTypes.node.isRequired
};
