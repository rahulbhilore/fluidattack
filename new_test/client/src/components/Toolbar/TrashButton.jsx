import React from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import { Link } from "react-router";
import { makeStyles } from "@material-ui/core/styles";

const useStyles = makeStyles(theme => ({
  link: {
    cursor: "pointer",
    color: theme.palette.OBI,
    marginRight: "20px",
    fontSize: ".75rem",
    lineHeight: "35px",
    "@media (min-width: 600px) and (max-width: 830px)": {
      marginRight: "10px"
    }
  }
}));

export default function TrashButton({ messageId, href }) {
  const classes = useStyles();

  return (
    <Link
      className={classes.link}
      to={href}
      data-component="delete-link"
      onlyActiveOnIndex
    >
      <Typography component="span">
        <FormattedMessage id={messageId} />
      </Typography>
    </Link>
  );
}

TrashButton.propTypes = {
  href: PropTypes.string.isRequired,
  messageId: PropTypes.string.isRequired
};
