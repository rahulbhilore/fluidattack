import {
  Button,
  Grid,
  InputAdornment,
  Stack,
  TextField,
  Theme,
  Tooltip,
  Typography
} from "@mui/material";
import { makeStyles } from "@mui/styles";
import React, { ChangeEvent, useContext, useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import FilesListActions from "../../../../../../../actions/FilesListActions";
import eyeSvg from "../../../../../../../assets/images/eye.svg";
import * as InputTypes from "../../../../../../../constants/appConstants/InputTypes";
import KudoSwitch from "../../../../../../Inputs/KudoSwitchNext/KudoSwitch";
import { SpecialDimensions } from "../../../../../../Inputs/KudoSwitchNext/types";
import StrengthChecker from "../../../../../../Inputs/StrengthChecker/StrengthChecker";
import SnackbarUtils from "../../../../../../Notifications/Snackbars/SnackController";
import { PermissionsDialogContext } from "../../../PermissionsDialogContext";

const DEFAULT_PASS = "123456";

const useStyles = makeStyles((theme: Theme) => ({
  passwordField: {
    marginTop: theme.spacing(1)
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
  eyeIcon: {
    width: "100%",
    height: "100%",
    zIndex: 1,
    cursor: "pointer"
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
  textField: {
    "& input": {
      color: theme.palette.DARK,
      height: "20px",
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
  }
}));

export default function SetPasswordProtection({
  switchDimensions,
  isPasswordRequired,
  setPassword,
  togglePassword
}: {
  isPasswordRequired: boolean;
  switchDimensions: SpecialDimensions;
  setPassword: (password: string) => void;
  togglePassword: (switchValue: boolean) => void;
}) {
  const {
    publicAccess: { isPublic }
  } = useContext(PermissionsDialogContext);
  const [value, setValue] = useState(isPasswordRequired ? DEFAULT_PASS : "");
  const [isDisabled, setDisabled] = useState(
    !value || !isPublic || isPasswordRequired
  );
  const [showTooltip, setTooltip] = useState(false);
  const [isEyeVisible, setEyeVisible] = useState(false);
  const [passwordComplexity, setPasswordComplexity] = useState(0);
  const [inputType, setInputType] = useState(InputTypes.PASSWORD);
  const [clearOnClick, setClearOnClick] = useState(isPasswordRequired);
  const classes = useStyles();

  const changePassword = () => {
    if (!isDisabled && (value || "").length > 0) {
      FilesListActions.getNonceForPublicLinkPassword()
        .then(({ nonce, realm, opaque }) => {
          const passwordHeader = FilesListActions.generatePasswordHeader({
            realm,
            nonce,
            opaque,
            password: value,
            ha1: undefined
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

  const handleComplexityChange = (complexity: number) => {
    setPasswordComplexity(complexity);
    setDisabled(complexity < 70);
  };

  const handleSwitch = (_: unknown, switchValue: boolean) => {
    setValue("");
    togglePassword(switchValue);
  };

  const handleValueChange = (e: ChangeEvent<HTMLInputElement>) => {
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

  return (
    <Stack direction="column" rowGap={1}>
      <Stack direction="row" alignItems="center" justifyContent="space-between">
        <Typography>
          <FormattedMessage id="setPasswordProtection" />
        </Typography>
        <KudoSwitch
          specialDimensions={switchDimensions}
          disabled={!isPublic}
          defaultChecked={isPasswordRequired && isPublic}
          onChange={handleSwitch}
        />
      </Stack>
      {isPasswordRequired && isPublic && (
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
                endAdornment: (
                  <InputAdornment
                    position="end"
                    style={{
                      margin: 0,
                      height: "38px",
                      maxHeight: "none"
                    }}
                  >
                    {isEyeVisible && isPasswordRequired && (
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
                    )}
                    <span className={classes.inputAddonBlock}>
                      <Button
                        variant="contained"
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
              (value !== DEFAULT_PASS || passwordComplexity !== 0) && (
                <StrengthChecker
                  value={value}
                  onChange={handleComplexityChange}
                />
              )}
          </Grid>
        </Tooltip>
      )}
    </Stack>
  );
}
