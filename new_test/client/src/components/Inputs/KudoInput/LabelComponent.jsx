import React from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import InputLabel from "@material-ui/core/InputLabel";
import StylingConstants from "../../../constants/appConstants/StylingConstants";

const useStyles = makeStyles(theme => ({
  root: {
    marginBottom: theme.typography.pxToRem(8),
    fontSize: theme.typography.pxToRem(12),
    color: theme.palette.OBI,
    fontFamily: theme.typography.fontFamily
  },
  error: {
    color: `${theme.palette.KYLO}!important`
  }
}));

export default function LabelComponent({ className, label, validationState }) {
  if (label.length === 0) return null;

  const classes = useStyles();

  let resultClass = clsx(classes.root, className);

  if (validationState === StylingConstants.ERROR)
    resultClass = clsx(resultClass, classes.error);

  return (
    <InputLabel className={resultClass}>
      <FormattedMessage id={label} />
    </InputLabel>
  );
}

LabelComponent.propTypes = {
  className: PropTypes.string,
  label: PropTypes.string.isRequired,
  validationState: PropTypes.string.isRequired
};

LabelComponent.defaultProps = {
  className: ""
};
