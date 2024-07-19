import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";

const useStyles = makeStyles(theme => ({
  root: {
    float: "right",
    display: "inline-block",
    color: theme.palette.LIGHT,
    fontWeight: "bold",
    lineHeight: "36px"
  }
}));

export default function Loading() {
  const classes = useStyles();
  return (
    <Typography variant="body1" className={classes.root}>
      <FormattedMessage id="analyzing" />
    </Typography>
  );
}
