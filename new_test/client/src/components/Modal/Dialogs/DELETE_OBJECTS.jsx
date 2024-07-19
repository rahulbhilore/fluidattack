import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import Typography from "@mui/material/Typography";
import List from "@mui/material/List";
import ListItem, { listItemClasses } from "@mui/material/ListItem";
import { makeStyles } from "@mui/styles";
import clsx from "clsx";
import FilesListActions from "../../../actions/FilesListActions";
import TemplatesActions from "../../../actions/TemplatesActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import Loader from "../../Loader";
import MainFunctions from "../../../libraries/MainFunctions";
import FontsActions from "../../../actions/FontsActions";
import ModalActions from "../../../actions/ModalActions";
import ProcessActions from "../../../actions/ProcessActions";
import Processes from "../../../constants/appConstants/Processes";
import SearchActions from "../../../actions/SearchActions";
import { BLOCK, LIBRARY } from "../../../stores/BlocksStore";
import BlocksActions from "../../../actions/BlocksActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import { FontsStore } from "../../../stores/resources/fonts/FontsStore";
import { TemplatesStore } from "../../../stores/resources/templates/TempatesStore";
import blocksStore from "../../../stores/resources/blocks/BlocksStore";

const dummyCheckFunction = () => true;
const useStyles = makeStyles(theme => ({
  body: {
    padding: 0
  },
  caption: {
    fontSize: theme.typography.pxToRem(14),
    textAlign: "center",
    margin: theme.spacing(2, 0)
  },
  text: {
    color: theme.palette.DARK
  },
  list: {
    padding: theme.spacing(1, 0),
    borderTop: `solid 1px ${theme.palette.DARK}`
  },
  listItem: {
    [`&.${listItemClasses.root}`]: {
      fontWeight: "bold",
      fontSize: theme.typography.pxToRem(12),
      padding: theme.spacing(0.5),
      justifyContent: "center"
    }
  }
}));

export default function deleteObject({ info }) {
  const [isLoading, setLoading] = useState(true);
  const [message, setMessage] = useState(null);
  const [isDeleteHandlerDisabled, setHandlerDisable] = useState(true);

  const handleSubmit = () => {
    if (isDeleteHandlerDisabled) return;
    const { list, type, templateType, data } = info;
    switch (type) {
      case BLOCK: {
        if (list.length > 0) {
          const { libId } = list[0];
          const ids = list.map(ent => ent.id).filter(v => (v || "").length > 0);
          BlocksActions.deleteMultipleBlocks(ids, libId).then(() => {
            // Case => If a user is a member of the org then the user can see all the blocks/libraries owned by its org, but the org
            //         admin can explicitly shared the block/library with any org member.
            //         So, now if the member tries to remove share for that item, we will unshare that item from that user
            //         (which is explicitly shared by the admin), but user is still the member of the org, so he/she can still see that item.
            //         And now as we don't delete the process, then it still persists.
            // AS : End the process for all ids of blocks and Libraries (XENON-64642)
            ids.forEach(id => {
              ProcessActions.end(id);
            });
          });
        }
        ModalActions.hide();
        break;
      }
      case LIBRARY: {
        if (list.length > 0) {
          const ids = list.map(ent => ent.id).filter(v => (v || "").length > 0);
          BlocksActions.deleteMultipleBlockLibraries(ids).then(() => {
            ids.forEach(id => {
              ProcessActions.end(id);
            });
          });
        }
        ModalActions.hide();
        break;
      }
      case "files": {
        const selected = _.groupBy(list, "type");
        const forcedIdList = {
          folders: (selected.folder || []).map(
            folder => folder._id || folder.id
          ),
          files: (selected.file || []).map(file => file._id || file.id)
        };
        list.forEach(item => {
          FilesListActions.modifyEntity(item._id, {
            processId: item._id
          });
          ProcessActions.start(item._id, Processes.DELETE);
        });
        FilesListActions.deleteSelected(forcedIdList, false).then(() => {
          list.forEach((item, index) => {
            ProcessActions.end(item._id);
            FilesListActions.deleteEntity(item._id, index === list.length - 1);
          });
          FilesListActions.checkNewFilesPage();
          FilesListActions.deleteRecentEntity(forcedIdList.files);
          SnackbarUtils.alertOk({ id: "successfullyDeleted" });
        });
        ModalActions.hide();
        break;
      }
      case "search": {
        const { tableId } = data;
        const selected = _.groupBy(list, "type");
        const forcedIdList = {
          folders: (selected.folder || []).map(
            folder => folder._id || folder.id
          ),
          files: (selected.file || []).map(file => file._id || file.id)
        };
        list.forEach(item => {
          FilesListActions.modifyEntity(item._id, {
            processId: item._id
          });
          ProcessActions.start(item._id, Processes.DELETE);
        });
        FilesListActions.deleteSelected(forcedIdList, false).then(() => {
          list.forEach(item => {
            ProcessActions.end(item._id);
            SearchActions.removeFromSearchResults(tableId, item);
          });
          SnackbarUtils.alertOk({ id: "successfullyDeleted" });
        });
        ModalActions.hide();
        break;
      }
      case "templates": {
        list.forEach(entity => {
          const { _id } = entity;
          const activeStore = TemplatesStore.activeStorage;
          activeStore.deleteTemplate(_id).catch(() => {
            SnackbarUtils.alertError({ id: "error" });
          });
        });
        ModalActions.hide();
        break;
      }
      case "oldTemplates": {
        TemplatesActions.deleteTemplates(_.pluck(list, "id"), templateType);
        ModalActions.hide();
        break;
      }
      case "fonts": {
        list.forEach(entity => {
          const { _id } = entity;
          const activeStore = FontsStore.activeStorage;
          activeStore.deleteFont(_id).catch(() => {
            SnackbarUtils.alertError({ id: "error" });
          });
        });
        ModalActions.hide();
        break;
      }
      case "oldFonts": {
        list.forEach(entity => {
          const { _id } = entity;
          FontsActions.remove(_id).catch(() => {
            SnackbarUtils.alertError({ id: "error" });
          });
        });
        ModalActions.hide();
        break;
      }
      case "blocks": {
        list.forEach(entity => {
          const { _id } = entity;
          blocksStore.deleteBlock(_id).catch(() => {
            SnackbarUtils.alertError({ id: "error" });
          });
        });
        ModalActions.hide();
        break;
      }
      default:
        break;
    }
  };

  useEffect(() => {
    const disableTimeout = setTimeout(() => {
      setHandlerDisable(false);
    }, 0);
    return () => {
      clearTimeout(disableTimeout);
    };
  }, []);

  useEffect(() => {
    const { list, type } = info;

    let messageToShow = null;

    if (type === "fonts") {
      messageToShow = <FormattedMessage id="deleteFontsQuestion" />;

      setMessage(messageToShow);
      setLoading(false);

      return;
    }

    let selectedCounts = _.countBy(list, item => item.type);

    // Delete messages, based on count of selected objects (selectedCounts)
    if (selectedCounts.block > 0 || selectedCounts.library > 0) {
      selectedCounts = _.defaults(selectedCounts, { block: 0, library: 0 });
      if (selectedCounts.block === 1 && selectedCounts.library === 0) {
        // single block
        messageToShow = <FormattedMessage id="deleteBlockQuestion" />;
      } else if (selectedCounts.library === 1 && selectedCounts.block === 0) {
        // single library
        messageToShow = <FormattedMessage id="deleteBlockLibraryQuestion" />;
      } else if (selectedCounts.library === 0) {
        // multiple blocks
        messageToShow = <FormattedMessage id="deleteMultipleBlocksQuestion" />;
      } else if (selectedCounts.block === 0) {
        // multiple libraries
        messageToShow = (
          <FormattedMessage id="deleteMultipleBlockLibrariesQuestion" />
        );
      } else {
        // multiple different objects
        messageToShow = <FormattedMessage id="deleteMultipleObjectsQuestion" />;
      }
    } else if (selectedCounts.template > 0) {
      messageToShow = <FormattedMessage id="deleteTemplateQuestion" />;
    } else if (selectedCounts.file > 0) {
      if (selectedCounts.folder > 0) {
        messageToShow = <FormattedMessage id="deleteMultipleObjectsQuestion" />;
      } else {
        messageToShow = <FormattedMessage id="deleteFileQuestion" />;
      }
    } else {
      messageToShow = <FormattedMessage id="deleteFolderQuestion" />;
    }

    setMessage(messageToShow);
    setLoading(false);
  }, [info]);

  useEffect(() => {
    if (!isLoading) {
      const submitButton = document.querySelector(
        ".dialog button[type='submit']"
      );
      if (submitButton) submitButton.focus();
    }
  }, [isLoading]);

  const classes = useStyles();
  const { list } = info;
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="deleteObjectsForm"
          checkFunction={dummyCheckFunction}
          onSubmitFunction={handleSubmit}
          checkOnMount
        >
          {isLoading ? (
            <Loader message={<FormattedMessage id="analyzing" />} isModal />
          ) : (
            <>
              <Typography
                variant="body1"
                className={clsx(classes.text, classes.caption)}
              >
                {message}
              </Typography>
              <List className={classes.list}>
                {list.map(item => (
                  <ListItem
                    key={item.id}
                    className={clsx(classes.text, classes.listItem)}
                  >
                    {MainFunctions.shrinkString(item.name, 60)}
                  </ListItem>
                ))}
              </List>
            </>
          )}
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton formId="deleteObjectsForm" isSubmit isDisabled={false}>
          <FormattedMessage id="delete" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
