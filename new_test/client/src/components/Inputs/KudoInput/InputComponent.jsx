import React from "react";
import clsx from "clsx";
import { useIntl } from "react-intl";
import TextField from "@material-ui/core/TextField";
import makeStyles from "@material-ui/core/styles/makeStyles";
import StylingConstants from "../../../constants/appConstants/StylingConstants";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import * as InputPropTypes from "./InputPropTypes";

const useStyles = makeStyles(theme => ({
  root: {
    width: "100%",
    "& .MuiInputBase-input": {
      width: "100%",
      padding: `${theme.typography.pxToRem(10)} ${theme.typography.pxToRem(8)}`,
      height: theme.typography.pxToRem(36),
      fontFamily: theme.typography.fontFamily,
      border: `${theme.typography.pxToRem(1)} solid ${theme.palette.REY}`,
      color: theme.palette.CLONE,
      backgroundColor: theme.palette.LIGHT,
      transition: theme.transitions.create(["border-color"]),
      boxShadow: "none",
      borderRadius: 0,
      boxSizing: "border-box",
      userSelect: "text",

      "&[disabled]": {
        color: theme.palette.REY
      }
    },
    "& .MuiInputBase-input::placeholder": {
      color: theme.palette.VADER
    },
    "& .MuiInputBase-input:hover, & .MuiInputBase-input:focus, & .MuiInputBase-input:active":
      {
        color: `${theme.palette.OBI}`,
        borderColor: `${theme.palette.OBI}`,
        transition: theme.transitions.create(["border-color"])
      },
    "& .MuiInput-underline:before, & .MuiInput-underline:after, & .MuiInput-underline:hover:not(.Mui-disabled):before":
      {
        border: "none"
      },
    "& .MuiInput-underline:before:hover, & .MuiInput-underline:before:focus": {
      border: "none"
    }
  },
  error: {
    "& .MuiInputBase-input": {
      border: `1px solid ${theme.palette.KYLO}`,
      color: theme.palette.KYLO
    },
    "& .MuiInputBase-input:hover, & .MuiInputBase-input:focus, & .MuiInputBase-input:active":
      {
        color: `${theme.palette.KYLO}!important`,
        borderColor: `${theme.palette.KYLO}!important`
      }
  }
}));

export default function InputComponent({
  type,
  id,
  onClick,
  name,
  placeHolder,
  showPlaceHolder,
  label,
  autoComplete,
  autoFocus,
  disabled,
  maxLength,
  required,
  readOnly,
  value,
  emitChangeAction,
  handleChange,
  min,
  max,
  inputDataComponent,
  classes,
  validationState
}) {
  const intl = useIntl();
  const innerClasses = useStyles();
  const placeholder =
    (placeHolder.length > 0 && label.length === 0) || showPlaceHolder
      ? intl.formatMessage({ id: placeHolder })
      : "";

  let resultClass = clsx(
    innerClasses.root,
    "kudoInput",
    validationState,
    classes.input
  );

  if (validationState === StylingConstants.ERROR)
    resultClass = clsx(resultClass, innerClasses.error);

  // 06/01/20 DK - TEXTAREA used in SEND_FEEDBACK
  if (type === InputTypes.TEXTAREA) {
    return (
      <TextField
        onBlur={emitChangeAction}
        componentClass="textarea"
        className={resultClass}
        id={id}
        value={value}
        onChange={handleChange}
        onClick={onClick}
        name={name}
        required={required}
        placeholder={placeholder}
        autoFocus={autoFocus}
        disabled={disabled}
        inputProps={{
          maxLength
        }}
        data-component={inputDataComponent}
      />
    );
  }
  // 06/01/20 DK - NUMBER used in ViewOnlyLinksSettings
  if (type === InputTypes.NUMBER) {
    return (
      <TextField
        onBlur={emitChangeAction}
        type={type}
        className={resultClass}
        id={id}
        value={value}
        onChange={handleChange}
        onClick={onClick}
        name={name}
        placeholder={placeholder}
        autoComplete={autoComplete}
        autoFocus={autoFocus}
        disabled={disabled}
        inputProps={{
          min,
          max,
          maxLength
        }}
        data-component={inputDataComponent}
      />
    );
  }
  return (
    <TextField
      onBlur={emitChangeAction}
      type={type}
      className={resultClass}
      id={id}
      value={value}
      onChange={handleChange}
      onClick={onClick}
      name={name}
      placeholder={placeholder}
      autoComplete={autoComplete}
      autoFocus={autoFocus}
      disabled={disabled}
      inputProps={{
        maxLength
      }}
      readOnly={readOnly}
      data-component={inputDataComponent}
    />
  );
}

InputComponent.propTypes = InputPropTypes.propTypes;
InputComponent.defaultProps = InputPropTypes.defaultProps;
