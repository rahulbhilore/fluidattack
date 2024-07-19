import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Box } from "@material-ui/core";
import uploadSVG from "../../../assets/images/upload.svg";
import FontsActions from "../../../actions/FontsActions";
import UserInfoStore from "../../../stores/UserInfoStore";
import FileDragAndDrop from "../../Toolbar/FileDragAndDrop";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  root: {
    margin: "0px 16px 4px 29px",
    paddingBottom: "0px",
    borderBottom: `1px solid ${theme.palette.BORDER_COLOR}`
  },
  button: {
    border: "none",
    boxShadow: "none",
    borderRadius: 0,
    padding: 0,
    margin: "20px 0 15px 0",
    backgroundColor: "transparent",
    "&:hover .st0": {
      fill: theme.palette.OBI
    },
    "&[disabled] .st0": {
      fill: theme.palette.REY
    }
  },
  input: {
    display: "none!important"
  },
  icon: {
    maxWidth: "32px!important",
    height: "32px!important",
    marginBottom: "-1px"
  }
}));

let inputRef = null;

export default function FontsToolbar({ type }) {
  const classes = useStyles();

  const uploadHandler = (event, eventFiles) => {
    const files = eventFiles || event.target.files;

    if (type === "custom") {
      Array.from(files).forEach(file => FontsActions.uploadCustomFont(file));
    } else {
      Array.from(files).forEach(file =>
        FontsActions.uploadCompanyFont(
          UserInfoStore.getUserInfo("company")?.id,
          file
        )
      );
    }
  };

  const buttonClick = () => {
    if (!inputRef) return;
    inputRef.click();
  };

  if (type === "company") {
    const companyInfo = UserInfoStore.getUserInfo("company");
    if (!companyInfo || !companyInfo.isAdmin) return null;
  }
  return (
    <Box className={classes.root}>
      <input
        name="file"
        type="file"
        className={classes.input}
        accept=".ttf,.ttc,.shx"
        ref={ref => {
          inputRef = ref;
        }}
        onChange={uploadHandler}
        multiple
      />
      <button
        className={classes.button}
        onClick={buttonClick}
        data-component="uploadFontButton"
        type="button"
      >
        <img className={classes.icon} src={uploadSVG} alt="Upload font" />
      </button>
    </Box>
  );
}

FontsToolbar.propTypes = {
  type: PropTypes.string
};

FontsToolbar.defaultProps = {
  type: "custom"
};
