import React from "react";
import Typography from "@material-ui/core/Typography";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";

const useStyles = makeStyles(theme => ({
  root: {
    fontSize: "12px",
    textAlign: "left",
    fontWeight: "bold",
    borderBottom: "solid 1px #cfcfcf",
    color: theme.palette.JANGO,
    marginTop: theme.spacing(4),
    marginBottom: theme.spacing(2),
    paddingBottom: theme.spacing(1)
  }
}));

export default function BlockHeading({ messageId }) {
  const classes = useStyles();
  if (!messageId.length) return null;
  return (
    <Typography variant="h3" className={classes.root}>
      <FormattedMessage id={messageId} />
    </Typography>
  );
}

BlockHeading.propTypes = {
  messageId: PropTypes.string.isRequired
};
