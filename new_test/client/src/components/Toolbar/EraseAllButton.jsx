import React, { useEffect, useState } from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import { makeStyles } from "@material-ui/core/styles";
import { CircularProgress } from "@mui/material";
import WarningAmberIcon from "@mui/icons-material/WarningAmber";
import Tooltip from "@material-ui/core/Tooltip";

import FilesListActions from "../../actions/FilesListActions";
import FilesListStore, { CONTENT_UPDATED } from "../../stores/FilesListStore";
import {
  GET_TRASH_FILES_COUNT,
  GET_TRASH_FILES_COUNT_FAIL,
  GET_TRASH_FILES_COUNT_SUCCESS,
  GET_FOLDER_CONTENT,
  GET_FOLDER_CONTENT_SUCCESS
} from "../../constants/FilesListConstants";
import UserInfoStore from "../../stores/UserInfoStore";
import MainFunctions from "../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  link: {
    cursor: "pointer",
    color: theme.palette.OBI,
    marginRight: "20px",
    fontSize: ".75rem",
    lineHeight: "35px",
    "@media (min-width: 600px) and (max-width: 830px)": {
      marginRight: "10px"
    }
  },
  onLoading: {
    cursor: "default",
    color: "#ccc"
  },
  spinner: {
    width: "20px!important",
    height: "20px!important",
    marginRight: "10px"
  },
  warning: {
    color: theme.palette.YELLOW_BUTTON,
    marginRight: "10px"
  }
}));

export default function EraseAllButton({ messageId, onClick }) {
  const classes = useStyles();
  const [isLoading, setIsLoading] = useState(true);
  const [isHidden, setIsHidden] = useState(false);
  const [isHiddenByFilterFiles, setIsHiddenByFilterFiles] = useState(false);

  const handleClick = () => {
    if (isLoading) return;

    onClick();
  };

  useEffect(() => {
    const onGetTrashBegin = () => {
      setIsLoading(true);
    };

    const onGetContentBegin = () => {
      setIsLoading(true);
      setIsHiddenByFilterFiles(false);
    };

    const onGetTrashFinished = number => {
      if (!number) {
        setIsHidden(true);
      }

      setIsLoading(false);
      setIsHiddenByFilterFiles(true);
    };

    const onGetFolderContentFinished = () => {
      const { type, id } = UserInfoStore.getUserInfo("storage");
      const storageType = MainFunctions.serviceNameToStorageCode(type);
      const currentObjectId = `${storageType}+${id}+-1`;
      const currentFilter = UserInfoStore.getUserInfo("fileFilter");

      const countOfEntities =
        FilesListStore.getTreeData(currentObjectId).length;

      if (countOfEntities) {
        setIsLoading(false);
        return;
      }

      if (currentFilter === "allFiles" && countOfEntities === 0) {
        setIsHidden(true);
        return;
      }

      FilesListActions.getTrashFilesCount();
    };

    FilesListStore.addEventListener(GET_FOLDER_CONTENT, onGetContentBegin);
    FilesListStore.addEventListener(
      GET_FOLDER_CONTENT_SUCCESS,
      onGetFolderContentFinished
    );

    FilesListStore.addEventListener(GET_TRASH_FILES_COUNT, onGetTrashBegin);
    FilesListStore.addEventListener(
      GET_TRASH_FILES_COUNT_SUCCESS,
      onGetTrashFinished
    );
    FilesListStore.addEventListener(
      GET_TRASH_FILES_COUNT_FAIL,
      onGetTrashFinished
    );
    FilesListStore.addEventListener(
      CONTENT_UPDATED,
      onGetFolderContentFinished
    );

    return () => {
      FilesListStore.removeEventListener(GET_FOLDER_CONTENT, onGetContentBegin);
      FilesListStore.removeEventListener(
        GET_FOLDER_CONTENT_SUCCESS,
        onGetFolderContentFinished
      );

      FilesListStore.removeEventListener(
        GET_TRASH_FILES_COUNT,
        onGetTrashBegin
      );
      FilesListStore.removeEventListener(
        GET_TRASH_FILES_COUNT_SUCCESS,
        onGetTrashFinished
      );
      FilesListStore.removeEventListener(
        GET_TRASH_FILES_COUNT_FAIL,
        onGetTrashFinished
      );
      FilesListStore.removeEventListener(
        CONTENT_UPDATED,
        onGetFolderContentFinished
      );
    };
  }, []);

  if (isHidden) return null;

  return (
    <>
      {isLoading ? <CircularProgress className={classes.spinner} /> : null}
      {isHiddenByFilterFiles ? (
        <Tooltip title={<FormattedMessage id="trashFilesByFilter" />}>
          <WarningAmberIcon className={classes.warning} />
        </Tooltip>
      ) : null}
      <Typography
        color="#000"
        className={clsx(classes.link, isLoading ? classes.onLoading : null)}
        component="span"
        onClick={handleClick}
      >
        <FormattedMessage id={messageId} />
      </Typography>
    </>
  );
}

EraseAllButton.propTypes = {
  onClick: PropTypes.func.isRequired,
  messageId: PropTypes.string.isRequired
};
