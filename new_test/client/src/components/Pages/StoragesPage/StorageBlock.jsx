import React from "react";
import PropTypes from "prop-types";
import { IconButton, Tooltip, Typography } from "@material-ui/core";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import StorageCaption from "./StorageCaption";
import closeButton from "../../../assets/images/Commander/Close.svg";
import closeButtonBlack from "../../../assets/images/Commander/Close_black.svg";
import MainFunctions from "../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.LIGHT,
    marginTop: theme.spacing(4)
  },
  img: {
    width: "30px",
    margin: theme.spacing(1, 3)
  },
  caption: {
    textAlign: "center",
    color: theme.palette.JANGO,
    fontSize: ".8rem",
    margin: theme.spacing(2)
  },
  images: {
    textAlign: "center"
  },
  accountName: {
    fontSize: ".75rem",
    color: theme.palette.JANGO,
    display: "inline-block"
  },
  accountBlock: {
    padding: theme.spacing(2),
    "&:not(:last-child)": {
      borderBottom: "1px solid #cfcfcf"
    }
  },
  crossButton: {
    padding: 0,
    float: "right"
  },
  cross: {
    height: "20px",
    width: "20px"
  }
}));

export default function StorageBlock({
  storageObject,
  connectStorage,
  isCommander,
  style,
  accounts,
  deleteStorage
}) {
  const classes = useStyles();
  return (
    <div
      className={classes.root}
      id={`storage_${storageObject.name.toLowerCase()}`}
    >
      <StorageCaption
        storageObject={storageObject}
        connectStorage={connectStorage}
        isCommander={isCommander}
        style={style}
      />
      {accounts.map(integratedAccount => (
        <div
          className={classes.accountBlock}
          key={window.btoa(
            MainFunctions.convertStringToBinary(
              `${storageObject.name}_subAccount_${
                integratedAccount[`${storageObject.name}_username`]
              }`
            )
          )}
        >
          <Typography variant="body1" className={classes.accountName}>
            {integratedAccount[`${storageObject.name}_username`]}
          </Typography>
          <Tooltip
            placement="top"
            title={<FormattedMessage id="removeAccount" />}
          >
            <IconButton
              data-component="remove-account"
              className={classes.crossButton}
              onClick={e => {
                deleteStorage(storageObject.name, integratedAccount, e);
              }}
            >
              <img
                className={classes.cross}
                src={
                  !isCommander || style === "light"
                    ? closeButtonBlack
                    : closeButton
                }
                alt="Remove account"
              />
            </IconButton>
          </Tooltip>
        </div>
      ))}
    </div>
  );
}

StorageBlock.propTypes = {
  storageObject: PropTypes.shape({
    name: PropTypes.string.isRequired,
    displayName: PropTypes.string.isRequired
  }).isRequired,
  accounts: PropTypes.arrayOf(
    PropTypes.objectOf(
      PropTypes.oneOfType([
        PropTypes.string,
        PropTypes.arrayOf(PropTypes.string)
      ])
    )
  ),
  connectStorage: PropTypes.func.isRequired,
  deleteStorage: PropTypes.func.isRequired,
  isCommander: PropTypes.bool,
  style: PropTypes.string
};

StorageBlock.defaultProps = {
  accounts: [],
  isCommander: false,
  style: ""
};
