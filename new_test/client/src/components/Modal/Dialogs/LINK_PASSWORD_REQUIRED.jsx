import React, { useState } from "react";
import { FormattedMessage } from "react-intl";
import md5 from "md5";
import { InputAdornment, TextField, Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import Storage from "../../../utils/Storage";
import FilesListActions from "../../../actions/FilesListActions";
import ModalActions from "../../../actions/ModalActions";
import eyeSvg from "../../../assets/images/eye.svg";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  caption: {
    marginBottom: theme.spacing(1)
  },
  eyeIconBlock: {
    position: "absolute",
    width: 0,
    height: 0,
    backgroundColor: "transparent",
    border: "none",
    zIndex: 3,
    top: 0
  },
  eyeIcon: {
    position: "absolute",
    width: "40px",
    maxWidth: "40px",
    height: "40px",
    top: "-2px",
    right: "14px",
    zIndex: 1,
    cursor: "pointer"
  },
  textField: {
    "& input": {
      color: theme.palette.DARK,
      height: `${36 - theme.spacing(2)}px`,
      padding: theme.spacing(1, 1),
      fontSize: theme.typography.pxToRem(14),
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

export default function publicLinkPassword({ info }) {
  const [password, setPassword] = useState("");
  const [isEyeVisible, setEyeVisibility] = useState(false);
  const [inputType, setInputType] = useState(InputTypes.PASSWORD);
  const classes = useStyles();
  const handleSubmit = () => {
    const { fileId, token } = info;
    FilesListActions.getNonceForPublicLinkPassword()
      .then(({ nonce, realm, opaque }) => {
        const passwordHeader = FilesListActions.generatePasswordHeader({
          realm,
          nonce,
          opaque,
          password
        });
        const ha1 = md5(`${realm}:${password}`);
        Storage.store(btoa(`password_${fileId}_${token}`), ha1);
        FilesListActions.getObjectInfo(fileId, "file", {
          token,
          password: passwordHeader
        })
          .then(() => {
            FilesListActions.reloadDrawing(true);
            ModalActions.hide();
          })
          .catch(() => {
            SnackbarUtils.alertError({ id: "passwordNotMatch" });
            ModalActions.passwordRequiredForLink(fileId, token);
          });
      })
      .catch(() => {
        SnackbarUtils.alertError({ id: "passwordNotMatch" });
        ModalActions.passwordRequiredForLink(fileId, token);
      });
  };

  const eyeShowPassword = () => {
    setInputType(InputTypes.TEXT);
  };

  const eyeHidePassword = () => {
    setInputType(InputTypes.PASSWORD);
  };

  const handleValueChange = e => {
    const newValue = e.target.value;
    setPassword(newValue);
    setEyeVisibility(newValue.length > 0);
  };

  const eyeButton = isEyeVisible ? (
    <button
      onMouseDown={eyeShowPassword}
      onMouseUp={eyeHidePassword}
      onMouseLeave={eyeHidePassword}
      className={classes.eyeIconBlock}
      type="button"
      id="eyeIconBlock"
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
    <>
      <DialogBody>
        <KudoForm
          id="publicLinkPasswordForm"
          onSubmitFunction={handleSubmit}
          checkFunction={() => true}
          checkOnMount
        >
          <Typography variant="body1" className={classes.caption}>
            <FormattedMessage id="publicPasswordMessage" />
          </Typography>
          <TextField
            id="link_password"
            name="link_password"
            autoFocus
            className={classes.textField}
            type={inputType}
            variant="outlined"
            onChange={handleValueChange}
            value={password}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">{eyeButton}</InputAdornment>
              )
            }}
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="publicLinkPasswordForm" isDisabled={false}>
          <FormattedMessage id="ok" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
