import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import makeStyles from "@material-ui/core/styles/makeStyles";
import FormHelperText from "@material-ui/core/FormHelperText";
import { FormattedMessage } from "react-intl";
import StylingConstants from "../../../constants/appConstants/StylingConstants";

const useStyles = makeStyles(theme => ({
  root: {
    color: theme.palette.CLONE
  },
  error: {
    color: theme.palette.KYLO
  }
}));

export default function HelperTextComponent({
  className,
  helpText,
  validationState
}) {
  const classes = useStyles();

  let resultClass = clsx(className, classes.root);

  if (validationState === StylingConstants.ERROR)
    resultClass = clsx(resultClass, classes.error);

  return (
    <FormHelperText className={resultClass}>
      {helpText ? (
        <FormattedMessage id={helpText.id} values={helpText.values} />
      ) : (
        ""
      )}
    </FormHelperText>
  );
}

HelperTextComponent.propTypes = {
  className: PropTypes.string,
  helpText: PropTypes.objectOf(PropTypes.oneOfType([PropTypes.string])),
  validationState: PropTypes.string
};

HelperTextComponent.defaultProps = {
  className: "",
  helpText: {},
  validationState: ""
};
