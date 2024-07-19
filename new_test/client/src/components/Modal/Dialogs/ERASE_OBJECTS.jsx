import React, { useEffect, useState, useLayoutEffect } from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import Typography from "@material-ui/core/Typography";
import List from "@material-ui/core/List";
import ListItem from "@material-ui/core/ListItem";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import FilesListActions from "../../../actions/FilesListActions";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import MainFunctions from "../../../libraries/MainFunctions";
import ModalActions from "../../../actions/ModalActions";
import ProcessActions from "../../../actions/ProcessActions";
import Processes from "../../../constants/appConstants/Processes";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const dummyCheckFunction = () => true;
const useStyles = makeStyles(theme => ({
  body: {
    paddingLeft: 0,
    paddingRight: 0
  },
  caption: {
    textAlign: "center",
    marginBottom: theme.spacing(1),
    "&, & > *": {
      fontSize: "1rem"
    }
  },
  text: {
    color: theme.palette.DARK
  },
  list: {
    paddingBottom: 0,
    paddingTop: theme.spacing(1),
    borderTop: `solid 1px ${theme.palette.DARK}`
  },
  listItem: {
    fontWeight: "bold",
    fontSize: ".9rem",
    padding: theme.spacing(0.5),
    justifyContent: "center"
  }
}));

export default function eraseObject({ info }) {
  const [message, setMessage] = useState(null);

  const handleSubmit = () => {
    const { objects } = info;
    const selected = _.groupBy(objects, "type");
    const forcedIdList = {
      folders: (selected.folder || []).map(folder => folder._id || folder.id),
      files: (selected.file || []).map(file => file._id || file.id)
    };
    objects.forEach(item => {
      FilesListActions.modifyEntity(item._id, {
        processId: item._id
      });
      ProcessActions.start(item._id, Processes.ERASE);
    });
    FilesListActions.eraseObjects(forcedIdList)
      .then(() => {
        objects.forEach((item, index) => {
          ProcessActions.end(item._id);
          FilesListActions.deleteEntity(item._id, index === objects.length - 1);
        });
        SnackbarUtils.alertOk({ id: "successfullyErased" });
      })
      .catch(() => {
        SnackbarUtils.alertError({ id: "errorsErasingEntities" });
      });
    ModalActions.hide();
  };

  const { objects } = info;

  useEffect(() => {
    const selectedCounts = _.countBy(objects, item => item.type);
    let messageToShow = null;
    // Delete messages, based on count of selected objects (selectedCounts)
    if (selectedCounts.template > 0) {
      messageToShow = <FormattedMessage id="eraseTemplateQuestion" />;
    } else if (selectedCounts.file > 0) {
      if (selectedCounts.folder > 0) {
        messageToShow = <FormattedMessage id="eraseMultipleObjectsQuestion" />;
      } else {
        messageToShow = <FormattedMessage id="eraseFileQuestion" />;
      }
    } else {
      messageToShow = <FormattedMessage id="eraseFolderQuestion" />;
    }

    setMessage(messageToShow);
  }, [objects]);

  useLayoutEffect(() => {
    const submitButton = document.querySelector(
      ".dialog button[type='submit']"
    );
    if (submitButton) submitButton.focus();
  }, []);
  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="eraseObjectsForm"
          checkFunction={dummyCheckFunction}
          onSubmitFunction={handleSubmit}
          checkOnMount
        >
          <>
            <Typography
              variant="body1"
              className={clsx(classes.text, classes.caption)}
            >
              {message}
              <br />
              <FormattedMessage
                id="erasingWarning"
                values={{ strong: IntlTagValues.strong }}
              />
            </Typography>
            <List className={classes.list}>
              {objects.map(item => (
                <ListItem
                  key={item.id}
                  className={clsx(classes.text, classes.listItem)}
                >
                  {MainFunctions.shrinkString(item.name, 60)}
                </ListItem>
              ))}
            </List>
          </>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit isDisabled={false} formId="eraseObjectsForm">
          <FormattedMessage id="erase" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
