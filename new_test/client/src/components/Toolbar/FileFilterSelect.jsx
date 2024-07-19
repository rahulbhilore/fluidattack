import React, { useState, useEffect } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import { Shortcuts } from "react-shortcuts";
import { makeStyles } from "@material-ui/core/styles";
import ToolbarSelect from "./ToolbarSelect";
import UserInfoStore, { INFO_UPDATE } from "../../stores/UserInfoStore";
import FilesListStore from "../../stores/FilesListStore";
import UserInfoActions from "../../actions/UserInfoActions";
import MainFunctions from "../../libraries/MainFunctions";
import FilesListActions from "../../actions/FilesListActions";

const useStyles = makeStyles(() => ({
  shortcutsWrapper: {
    display: "inline"
  }
}));

export default function FileFilterSelect(props) {
  const { isVisible } = props;

  if (!isVisible) return null;

  const classes = useStyles();

  const [value, setValue] = useState("allFiles");
  const [forceClose, setForceClose] = useState(false);
  const [isSelectOpened, setSelectOpened] = useState(false);

  const onUser = () => {
    setValue(UserInfoStore.getUserInfo("fileFilter"));
  };

  useEffect(() => {
    UserInfoStore.addChangeListener(INFO_UPDATE, onUser);
    UserInfoActions.getUserInfo();
    return () => {
      UserInfoStore.removeChangeListener(INFO_UPDATE, onUser);
    };
  }, []);

  const changeFileFilter = input => {
    const newFilterValue = input.target.value;
    const { storage, accountId, _id } = FilesListStore.getCurrentFolder();
    if (newFilterValue !== UserInfoStore.getUserInfo("fileFilter")) {
      setValue(newFilterValue);
      UserInfoActions.modifyUserInfo({ fileFilter: newFilterValue }).then(
        () => {
          const { storageType, storageId, objectId } =
            MainFunctions.parseObjectId(_id);
          FilesListActions.getFolderContent(
            storageType || storage,
            storageId || accountId,
            objectId,
            false,
            { isIsolated: false, recursive: false, usePageToken: false }
          );
        }
      );
    }
  };

  const onKeyPress = action => {
    if (!isSelectOpened) return;

    switch (action) {
      case "MOVE_UP": {
        switch (value) {
          case "allFiles":
            break;
          case "drawingsAndPdf":
            setValue("allFiles");
            break;
          case "drawingsOnly":
            setValue("drawingsAndPdf");
            break;
          default:
            break;
        }
        break;
      }
      case "MOVE_DOWN": {
        switch (value) {
          case "allFiles":
            setValue("drawingsAndPdf");
            break;
          case "drawingsAndPdf":
            setValue("drawingsOnly");
            break;
          case "drawingsOnly":
            break;
          default:
            break;
        }
        break;
      }
      case "SELECT": {
        if (value !== UserInfoStore.getUserInfo("fileFilter")) {
          UserInfoActions.modifyUserInfo({ fileFilter: value }).then(() => {
            const { storage, accountId, _id } =
              FilesListStore.getCurrentFolder();
            const { storageType, storageId, objectId } =
              MainFunctions.parseObjectId(_id);
            FilesListActions.getFolderContent(
              storageType || storage,
              storageId || accountId,
              objectId,
              false,
              { isIsolated: false, recursive: false, usePageToken: false }
            );
          });
        }
        setForceClose(true);
        setSelectOpened(false);
        break;
      }
      default:
        break;
    }
  };

  const clearForceClose = () => {
    setForceClose(false);
  };

  const onSelectOpen = () => {
    setSelectOpened(true);
  };

  const onSelectClose = () => {
    setSelectOpened(false);
  };

  return (
    <Shortcuts
      name="SELECT"
      handler={onKeyPress}
      className={classes.shortcutsWrapper}
      global
      targetNodeSelector="body"
    >
      <ToolbarSelect
        value={value}
        onChange={changeFileFilter}
        forceClose={forceClose}
        clearForceClose={clearForceClose}
        onSelectOpen={onSelectOpen}
        onSelectClose={onSelectClose}
        options={[
          {
            value: "allFiles",
            label: <FormattedMessage id="allFiles" />
          },
          {
            value: "drawingsAndPdf",
            label: <FormattedMessage id="drawingsAndPdf" />
          },
          {
            value: "drawingsOnly",
            label: <FormattedMessage id="drawingsOnly" />
          }
        ]}
        width="180px"
        mobileWidht="100vw!important"
      />
    </Shortcuts>
  );
}

FileFilterSelect.propTypes = {
  isVisible: PropTypes.bool.isRequired
};
