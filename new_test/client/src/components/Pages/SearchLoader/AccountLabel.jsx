import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import StorageIcons from "../../../constants/appConstants/StorageIcons";

const useStyles = makeStyles(theme => ({
  icon: {
    width: 25,
    height: 25,
    marginRight: theme.spacing(1),
    verticalAlign: "middle"
  }
}));

export default function AccountLabel({ storage, name }) {
  const classes = useStyles();
  return (
    <>
      <img
        src={StorageIcons[`${storage.toLowerCase()}ActiveSVG`]}
        alt={storage}
        className={classes.icon}
      />
      {name}
    </>
  );
}

AccountLabel.propTypes = {
  storage: PropTypes.string.isRequired,
  name: PropTypes.string.isRequired
};
