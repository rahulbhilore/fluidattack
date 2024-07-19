import React from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import Link from "@material-ui/core/Link";
import { Tooltip, Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Link as RouterLink } from "react-router";

const useStyles = makeStyles(theme => ({
  item: {
    fontSize: "12px",
    color: theme.palette.CLONE,
    "&:hover,&:focus,&:active": {
      textDecoration: "underline"
    }
  },
  lastItem: {
    color: theme.palette.OBI,
    "&:hover,&:focus,&:active": {
      textDecoration: "none"
    }
  }
}));

export default function SingleBreadcrumb({
  text,
  href,
  isLast,
  switchFolder,
  name,
  id
}) {
  const classes = useStyles();
  const handleSwitch = e => {
    e.preventDefault();
    switchFolder(text, id, false);
  };
  let linkItself = null;
  if (!isLast) {
    linkItself = (
      <Link
        to={href}
        onClick={handleSwitch}
        component={RouterLink}
        className={classes.item}
      >
        {text}
      </Link>
    );
  } else {
    linkItself = (
      <Typography className={clsx(classes.item, classes.lastItem)}>
        {text}
      </Typography>
    );
  }
  if ((name || "").length > 0 && name !== text) {
    return (
      <Tooltip placement="bottom" title={name}>
        {linkItself}
      </Tooltip>
    );
  }
  return linkItself;
}
SingleBreadcrumb.propTypes = {
  text: PropTypes.string.isRequired,
  href: PropTypes.string.isRequired,
  id: PropTypes.string.isRequired,
  name: PropTypes.string.isRequired,
  isLast: PropTypes.bool,
  switchFolder: PropTypes.func
};

SingleBreadcrumb.defaultProps = {
  isLast: false,
  switchFolder: null
};
