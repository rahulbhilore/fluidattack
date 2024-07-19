import React, { isValidElement, useEffect, useState } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import { DateTime } from "luxon";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import { InputAdornment, TextField, Typography } from "@material-ui/core";
import CopyButton from "./CopyButton";
import ExportToggle from "./ExportToggle";
import ExpirationBlock from "./ExpirationBlock";
import FilesListActions from "../../../../../actions/FilesListActions";
import ModalActions from "../../../../../actions/ModalActions";
import * as IntlTagValues from "../../../../../constants/appConstants/IntlTagValues";
import PasswordControl from "./PasswordControl";
import KudoSwitch from "../../../../Inputs/KudoSwitch/KudoSwitch";
import Loader from "../../../../Loader";

const useStyles = makeStyles(theme => ({
  root: {
    padding: theme.spacing(1.5)
  },
  linkBlock: {
    marginBottom: theme.spacing(2)
  },
  linkMessage: {
    margin: theme.spacing(2, 0),
    fontSize: theme.typography.pxToRem(13),
    textAlign: "center"
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
  inputAddon: {
    padding: 0,
    backgroundColor: "transparent",
    border: "none",
    height: "36px"
  },
  inputAddonBlock: {
    paddingLeft: theme.spacing(1),
    display: "inline-block",
    verticalAlign: "top",
    fontSize: theme.typography.pxToRem(12)
  },
  inputAddonButton: {
    textTransform: "uppercase",
    padding: `${theme.spacing(1)}px ${theme.spacing(2)}px !important`,
    borderRadius: 0,
    height: "36px",
    backgroundColor: `${theme.palette.OBI} !important`,
    borderColor: theme.palette.OBI,
    boxShadow: "none",
    fontSize: theme.typography.pxToRem(12),
    fontWeight: "bold",
    textShadow: "none"
  },
  disabledMessage: {
    marginBottom: theme.spacing(1)
  }
}));

export default function PublicAccess({
  fileId,
  isPublic: isPublicProp,
  link: linkProp,
  isExport: isExportProp,
  endTime: endTimeProp,
  isPasswordRequired: isPasswordRequiredProp,
  updateObjectInfo,
  isViewOnly,
  isDeleted,
  isExportAvailable,
  name
}) {
  const parsedTime = parseInt(endTimeProp || 0, 10);
  const [isPublic, setPublic] = useState(isPublicProp);
  const [link, setLink] = useState(linkProp);
  const [isExport, setExport] = useState(isExportProp);
  const [endTime, setEndTime] = useState(parsedTime);
  const [isPasswordRequired, setPasswordRequired] = useState(
    isPasswordRequiredProp
  );
  const [passwordHeader, setPassword] = useState("");
  const [isLoading, setLoading] = useState(false);

  const createPublicLink = () => {
    if (isPublic && !isViewOnly && !isDeleted) {
      setLoading(true);
      FilesListActions.createPublicLink({
        fileId,
        isExport,
        endTime,
        password:
          isPasswordRequired && passwordHeader.length > 0 ? passwordHeader : "",
        resetPassword: !isPasswordRequired
      }).then(newLink => {
        setLoading(false);
        if (
          !location.pathname.includes("app") &&
          !location.pathname.includes("search")
        )
          FilesListActions.modifyEntity(fileId, {
            public: true,
            link: newLink
          });
        setLink(newLink);
        setPassword("");
        updateObjectInfo();
      });
    }
  };

  /**
   * @description Generates or removes public link for file
   * @param isGenerate {boolean}
   * */
  const toggleSharedLink = isGenerate => {
    if (!isGenerate) {
      ModalActions.removePublicLink(fileId, name);
    } else {
      setPublic(true);
    }
  };

  const changeExportState = newExportState => {
    setExport(newExportState);
  };

  const setExpirationTime = dateObject => {
    if (dateObject.getTime() === 0) {
      setEndTime(0);
    } else {
      setEndTime(
        DateTime.fromMillis(dateObject.getTime()).endOf("day").toMillis()
      );
    }
  };

  const togglePassword = () => {
    if (isPasswordRequired) {
      setPasswordRequired(false);
    } else {
      setPasswordRequired(true);
    }
  };

  const setPasswordHeader = passwordHeaderVal => {
    setPassword(passwordHeaderVal);
  };

  useEffect(() => {
    // no need to trigger if endTime is before now
    if (endTime === 0 || endTime > Date.now()) {
      createPublicLink();
    }
  }, [isPublic, isExport, endTime]);

  useEffect(() => {
    if (!isPasswordRequired && isPasswordRequiredProp) {
      createPublicLink();
    }
  }, [isPasswordRequired]);
  useEffect(() => {
    if (isPasswordRequired && passwordHeader.length > 0) {
      createPublicLink();
    }
  }, [passwordHeader, isPasswordRequired]);

  const classes = useStyles();

  const disableMessage =
    !isPublic && !isViewOnly ? (
      <div
        className={classes.disabledMessage}
        data-component="linkIsDisabledNotification"
      >
        <FormattedMessage
          id="permissionsDisabledLinkInfo"
          values={{
            strong: IntlTagValues.strong,
            br: IntlTagValues.br
          }}
        />
      </div>
    ) : null;
  if (isLoading && location.pathname.includes("/app/")) return <Loader />;
  return (
    <Grid
      item
      xs={12}
      className={clsx(classes.root, `${isPublic ? "" : "disabled"}`)}
    >
      <Grid item xs={12} className={classes.linkBlock}>
        {disableMessage}
        {isValidElement(link) ? (
          <Typography variant="h3" className={classes.linkMessage}>
            {link}
          </Typography>
        ) : (
          <TextField
            className={classes.textField}
            variant="outlined"
            aria-readonly
            value={link}
            disabled={
              (!isPublic && !isViewOnly) ||
              (endTime > 0 && endTime < Date.now())
            }
            InputProps={{
              readOnly: true,
              inputProps: {
                "data-component": "publicLinkInput"
              },
              endAdornment: (
                <InputAdornment position="end">
                  {" "}
                  {link.length > 0 &&
                  isPublic &&
                  !isViewOnly &&
                  (endTime === 0 || endTime > Date.now()) ? (
                    <span className={classes.inputAddonBlock}>
                      <CopyButton
                        className={classes.inputAddonButton}
                        link={link}
                      />{" "}
                    </span>
                  ) : null}
                  {!isViewOnly && !isDeleted ? (
                    <div className={classes.inputAddonBlock}>
                      <KudoSwitch
                        id="sharedLinkStatus"
                        name="sharedLinkStatus"
                        onChange={toggleSharedLink}
                        defaultChecked={isPublic}
                        dataComponent="view-only-link-switch"
                        disabled={isLoading}
                        styles={{
                          formGroup: {
                            margin: "0 !important"
                          },
                          label: {
                            width: "100%",
                            margin: "2px 0 0 0 !important"
                          },
                          switch: {
                            width: "58px",
                            height: "32px",
                            margin: "0 !important",
                            marginTop: 10,
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
                    </div>
                  ) : null}
                </InputAdornment>
              )
            }}
          />
        )}
      </Grid>
      {isExportAvailable === true && isViewOnly === false ? (
        <ExportToggle
          changeExportState={changeExportState}
          isExport={isExport}
          disabled={!isPublic}
        />
      ) : null}
      {isViewOnly === false ? (
        <ExpirationBlock
          linkEndTime={new Date(endTime)}
          disabled={!isPublic}
          setExpirationTime={setExpirationTime}
        />
      ) : null}
      {isViewOnly === false ? (
        <PasswordControl
          disabled={!isPublic}
          wasPasswordSet={isPasswordRequiredProp}
          isPasswordRequired={isPasswordRequired}
          togglePassword={togglePassword}
          setPassword={setPasswordHeader}
        />
      ) : null}
    </Grid>
  );
}

PublicAccess.propTypes = {
  isViewOnly: PropTypes.bool,
  isDeleted: PropTypes.bool,
  isPublic: PropTypes.bool,
  fileId: PropTypes.string.isRequired,
  link: PropTypes.string,
  isExportAvailable: PropTypes.bool,
  isExport: PropTypes.bool,
  endTime: PropTypes.number,
  isPasswordRequired: PropTypes.bool,
  updateObjectInfo: PropTypes.func,
  name: PropTypes.string.isRequired
};

PublicAccess.defaultProps = {
  isViewOnly: true,
  isDeleted: false,
  isPublic: false,
  link: "",
  isExportAvailable: false,
  isExport: false,
  isPasswordRequired: false,
  endTime: 0,
  updateObjectInfo: () => null
};
