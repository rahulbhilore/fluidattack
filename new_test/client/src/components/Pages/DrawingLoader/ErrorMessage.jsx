import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Typography, Grid } from "@material-ui/core";
import MainFunctions from "../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.SNOKE,
    display: "flex",
    justifyContent: "center",
    alignItems: "center",
    textAlign: "center",
    height: "100%",
    width: "100%"
  },
  text: {
    fontSize: "1.25rem",
    color: theme.palette.LIGHT,
    marginBottom: theme.spacing(1)
  }
}));

export default function ErrorMessage({ messages, children }) {
  const classes = useStyles();
  return (
    <div className={classes.root}>
      <Grid item xs={12} sm={6}>
        {messages.map(message => (
          <Typography
            variant="body1"
            className={classes.text}
            key={MainFunctions.getStringHashCode(message)}
          >
            {message}
          </Typography>
        ))}
        {children}
      </Grid>
    </div>
  );
}

ErrorMessage.propTypes = {
  messages: PropTypes.arrayOf(
    PropTypes.oneOfType([PropTypes.node, PropTypes.string])
  ),
  children: PropTypes.node
};
ErrorMessage.defaultProps = { messages: [], children: null };
