/* eslint-disable no-unused-vars */
import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import Tooltip from "@material-ui/core/Tooltip";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import { Button, InputAdornment, TextField } from "@material-ui/core";
import KudoSwitch from "../../../../Inputs/KudoSwitch/KudoSwitch";
import * as InputTypes from "../../../../../constants/appConstants/InputTypes";
import FilesListActions from "../../../../../actions/FilesListActions";
import eyeSvg from "../../../../../assets/images/eye.svg";
import StrengthChecker from "../../../../Inputs/StrengthChecker/StrengthChecker";
import SnackbarUtils from "../../../../Notifications/Snackbars/SnackController";

const DEFAULT_PASS = "123456";

const useStyles = makeStyles(theme => ({
  root: {
    marginBottom: theme.spacing(1),
    padding: 0
  },
  input: {
    borderRadius: 0,
    height: "36px",
    zIndex: 0,
    backgroundColor: `${theme.palette.LIGHT} !important`
  },
  passwordField: {
    marginTop: theme.spacing(1)
  },
  inputAddon: {
    padding: 0,
    backgroundColor: "transparent",
    border: "none",
    height: "36px",
    verticalAlign: "top"
  },
  inputAddonBlock: {
    paddingLeft: theme.spacing(1),
    display: "inline-block",
    verticalAlign: "top"
  },
  inputAddonButton: {
    textTransform: "uppercase",
    padding: `${theme.spacing(1)}px ${theme.spacing(2)}px !important`,
    borderRadius: 0,
    height: "36px",
    backgroundColor: `${theme.palette.OBI} !important`,
    borderColor: theme.palette.OBI,
    boxShadow: "none",
    fontSize: theme.typography.pxToRem(13),
    fontWeight: "bold",
    textShadow: "none"
  },
  eyeIconBlock: {
    cursor: "pointer",
    position: "absolute",
    backgroundColor: "transparent",
    border: "none",
    zIndex: 3,
    height: "36px",
    width: "36px",
    padding: 0,
    right: "72px"
  },
  eyeIcon: {
    width: "100%",
    height: "100%",
    zIndex: 1,
    cursor: "pointer"
  },
  textField: {
    "& input": {
      color: theme.palette.DARK,
      height: `${36 - theme.spacing(2)}px`,
      padding: theme.spacing(1, 1),
      fontSize: theme.typography.pxToRem(12),
      border: `solid 1px ${theme.palette.REY}`,
      "&:hover,&:focus,&:active": {
        borderColor: theme.palette.OBI,
        color: theme.palette.OBI
      },
      "&[disabled]": {
        backgroundColor: theme.palette.REY,
        pointerEvents: "none"
      },
      "&.MuiOutlinedInput-input:-webkit-autofill": {
        caretColor: theme.palette.DARK,
        borderRadius: 0,
        boxShadow: `0 0 0 100px ${theme.palette.LIGHT} inset`,
        textFillColor: theme.palette.DARK
      }
    },
    "& > div": {
      padding: 0
    },
    "& fieldset": {
      border: "none !important"
    },
    width: "100%",
    borderRadius: 0,
    outline: "none !important"
  },
  strengthCheckRoot: {
    // this is bad "magic number", but I don't see a way to properly position it exactly under input
    // addon block width is 80 - 4px padding for the right segment
    width: "calc(100% - 76px)"
  }
}));

export default function PasswordControl({
  setPassword,
  disabled,
  isPasswordRequired,
  togglePassword
}) {
  const [value, setValue] = useState(isPasswordRequired ? DEFAULT_PASS : "");
  const [isDisabled, setDisabled] = useState(
    !value || disabled || isPasswordRequired
  );
  const [showTooltip, setTooltip] = useState(false);
  const [isEyeVisible, setEyeVisible] = useState(false);
  const [passwordComplexity, setPasswordComplexity] = useState(0);
  const [inputType, setInputType] = useState(InputTypes.PASSWORD);
  const [clearOnClick, setClearOnClick] = useState(isPasswordRequired);

  const changePassword = () => {
    if (!isDisabled && (value || "").length > 0) {
      FilesListActions.getNonceForPublicLinkPassword()
        .then(({ nonce, realm, opaque }) => {
          const passwordHeader = FilesListActions.generatePasswordHeader({
            realm,
            nonce,
            opaque,
            password: value
          });
          setPassword(passwordHeader);
          setDisabled(true);
          SnackbarUtils.alertOk({ id: "passwordHasBeenSet" });
        })
        .catch(() => {
          SnackbarUtils.alertError({ id: "errorSettingPasswordTryAgain" });
        });
    }
  };

  const eyeShowPassword = () => {
    setInputType(InputTypes.TEXT);
  };

  const eyeHidePassword = () => {
    setInputType(InputTypes.PASSWORD);
  };

  const handleClick = () => {
    if (clearOnClick) {
      setValue("");
      setClearOnClick(false);
    }
  };

  const handleComplexityChange = complexity => {
    setPasswordComplexity(complexity);
    setDisabled(complexity < 70);
  };

  const handleSwitch = switchValue => {
    setValue("");
    togglePassword(switchValue);
  };

  const handleValueChange = e => {
    const newValue = e.target.value;
    if (clearOnClick && newValue.startsWith(DEFAULT_PASS)) {
      setClearOnClick(false);
      setValue(newValue.substring(DEFAULT_PASS.length));
    } else {
      setValue(newValue);
    }
    if (newValue.length === 0) setDisabled(true);
  };

  useEffect(() => {
    const isSomethingEntered =
      value.length >= 1 && (value !== DEFAULT_PASS || passwordComplexity !== 0);
    const isTooltipRequired = passwordComplexity < 70 && isSomethingEntered;
    if (!showTooltip && isTooltipRequired) {
      setTooltip(true);
    } else if (showTooltip && !isTooltipRequired) {
      setTooltip(false);
    }
    setEyeVisible(isSomethingEntered);
  }, [value, passwordComplexity]);

  const classes = useStyles();

  const eyeButton =
    isEyeVisible && isPasswordRequired ? (
      <button
        className={classes.eyeIconBlock}
        onTouchStart={eyeShowPassword}
        onTouchEnd={eyeHidePassword}
        onMouseDown={eyeShowPassword}
        onMouseUp={eyeHidePassword}
        onMouseLeave={eyeHidePassword}
        type="button"
      >
        <img
          src={eyeSvg}
          className={classes.eyeIcon}
          alt="Root"
          draggable={false}
        />
      </button>
    ) : null;
  return (
    <Grid item xs={12} className={classes.root}>
      <KudoSwitch
        name="enablePassword"
        id="enablePassword"
        label="setPasswordProtection"
        defaultChecked={isPasswordRequired && !disabled}
        onChange={handleSwitch}
        disabled={disabled}
        dataComponent="enable-password-switch"
        styles={{
          formGroup: {
            margin: 0
          },
          label: {
            width: "100%",
            margin: "0 !important",
            "& .MuiTypography-root": {
              fontSize: 12,
              color: "#000000"
            }
          },
          switch: {
            width: "58px",
            height: "32px",
            margin: "0 !important",
            "& .MuiSwitch-thumb": {
              width: 20,
              height: 20
            },
            "& .Mui-checked": {
              transform: "translateX(23px)"
            },
            "& .MuiSwitch-switchBase": {
              padding: 1,
              color: "#FFFFFF",
              top: "5px",
              left: "6px"
            }
          }
        }}
      />
      {isPasswordRequired && !disabled ? (
        <Tooltip
          placement="top"
          arrow
          open={showTooltip}
          title={<FormattedMessage id="needStrongerPassword" />}
        >
          <Grid item xs={12} className={classes.passwordField}>
            <TextField
              className={classes.textField}
              type={inputType}
              variant="outlined"
              onChange={handleValueChange}
              value={value}
              onClick={handleClick}
              InputProps={{
                "data-component": "password-input-block",
                endAdornment: (
                  <InputAdornment
                    position="end"
                    style={{
                      margin: 0,
                      height: "38px",
                      maxHeight: "none"
                    }}
                  >
                    {eyeButton}
                    <span className={classes.inputAddonBlock}>
                      <Button
                        onClick={changePassword}
                        className={classes.inputAddonButton}
                        disabled={isDisabled}
                        data-component="set-password-button"
                      >
                        <FormattedMessage id="set" />
                      </Button>
                    </span>
                  </InputAdornment>
                )
              }}
            />
            {value.length > 0 &&
            (value !== DEFAULT_PASS || passwordComplexity !== 0) ? (
              <StrengthChecker
                classes={{ root: classes.strengthCheckRoot }}
                value={value}
                onChange={handleComplexityChange}
              />
            ) : null}
          </Grid>
        </Tooltip>
      ) : null}
    </Grid>
  );
}

PasswordControl.propTypes = {
  setPassword: PropTypes.func.isRequired,
  togglePassword: PropTypes.func.isRequired,
  isPasswordRequired: PropTypes.bool,
  disabled: PropTypes.bool
};

PasswordControl.defaultProps = {
  isPasswordRequired: false,
  disabled: true
};
