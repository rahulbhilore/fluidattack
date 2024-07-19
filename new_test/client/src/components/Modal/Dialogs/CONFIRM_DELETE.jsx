/**
 * Created by khizh on 8/25/2016.
 */
import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import List from "@material-ui/core/List";
import ListItem from "@material-ui/core/ListItem";
import Button from "@material-ui/core/Button";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import MainFunctions from "../../../libraries/MainFunctions";
import Loader from "../../Loader";
import ProcessActions from "../../../actions/ProcessActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const dummyCheckFunction = () => true;
const useStyles = makeStyles(theme => ({
  body: {
    paddingLeft: 0,
    paddingRight: 0
  },
  caption: {
    fontSize: theme.typography.pxToRem(14),
    textAlign: "center",
    marginBottom: theme.spacing(1)
  },
  text: {
    color: theme.palette.DARK,
    userSelect: "text"
  },
  warningText: {
    fontSize: theme.typography.pxToRem(12)
  },
  list: {
    paddingBottom: 0,
    paddingTop: theme.spacing(1),
    borderTop: `solid 1px ${theme.palette.DARK}`
  },
  listItem: {
    justifyContent: "center"
  },
  cancelButton: {
    backgroundColor: theme.palette.GREY_BACKGROUND,
    color: theme.palette.CLONE,
    height: "36px",
    textTransform: "uppercase",
    float: "left",
    padding: "10px 30px",
    fontSize: theme.typography.pxToRem(12)
  }
}));

export default function deleteConfirmationDialog({ info }) {
  const handleCancel = () => {
    const forcedIdList = {
      files: info.ids.files || [],
      folders: info.ids.folders || []
    };
    forcedIdList.files.forEach(fileId => {
      ProcessActions.end(fileId);
    });
    forcedIdList.folders.forEach(folderId => {
      ProcessActions.end(folderId);
    });
    ModalActions.hide();
  };
  const handleSubmit = () => {
    const forcedIdList = {
      files: info.ids.files || [],
      folders: info.ids.folders || []
    };
    FilesListActions.deleteSelected(forcedIdList, true).then(() => {
      SnackbarUtils.alertOk({ id: "successfullyDeleted" });
    });
    forcedIdList.files.forEach(fileId => {
      ProcessActions.end(fileId);
      FilesListActions.deleteEntity(fileId);
    });
    FilesListActions.deleteRecentEntity(forcedIdList.files);
    ModalActions.hide();
  };

  const [isLoading, setLoading] = useState(true);
  const [filesInfo, setFilesInfo] = useState({});

  useEffect(() => {
    const files = info.entities.files || [];
    if (files.length > 0) {
      const treeData = FilesListStore.getTreeData(
        FilesListStore.getCurrentFolder()._id
      );
      const infoPromises = files.map(file => {
        const knownInfo = treeData.find(i => i._id === file.fileId);
        if (knownInfo) {
          return Promise.resolve(knownInfo);
        }
        return FilesListActions.getObjectInfo(file.fileId, "file", {});
      });
      Promise.all(infoPromises).then(fileInfoArray => {
        const newFilesInfo = {};
        fileInfoArray.forEach(infoObject => {
          newFilesInfo[infoObject._id] = infoObject;
        });
        setFilesInfo(newFilesInfo);
        setLoading(false);
      });
    } else {
      setLoading(false);
    }
  }, []);

  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="deleteConfirmationForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
          checkFunction={dummyCheckFunction}
        >
          {isLoading ? <Loader isModal /> : null}
          {!isLoading && info.entities ? (
            <>
              <Typography
                variant="body1"
                className={clsx(classes.text, classes.caption)}
              >
                <FormattedMessage id="someFilesAreEditingByUsers" />
                <br />
                <FormattedMessage id="pleaseConfirmDeletion" />
              </Typography>
              <List className={classes.list}>
                {(info.entities.files || []).map(file => {
                  const fileInfo = filesInfo[file.fileId] || {};
                  return (
                    <ListItem key={file.fileId} className={classes.listItem}>
                      <Typography
                        className={clsx(classes.text, classes.warningText)}
                      >
                        <FormattedMessage
                          id="drawingIsBeingEditedByNow"
                          values={{
                            name: ` ${MainFunctions.shrinkString(
                              fileInfo.name || fileInfo.filename || "Unknown",
                              40
                            )} `,
                            editors: ` ${(file.editors || [])
                              .map(e => e.email || "")
                              .filter(v => v.length > 0)
                              .join(", ")} `,
                            strong: IntlTagValues.strong
                          }}
                        />
                      </Typography>
                    </ListItem>
                  );
                })}
              </List>
            </>
          ) : null}
        </KudoForm>
      </DialogBody>
      <DialogFooter showCancel={false}>
        <Button
          onClick={handleCancel}
          className={classes.cancelButton}
          data-component="modal-cancel-button"
        >
          <FormattedMessage id="cancel" />
        </Button>
        <KudoButton formId="deleteConfirmationForm" isSubmit isDisabled={false}>
          <FormattedMessage id="delete" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
