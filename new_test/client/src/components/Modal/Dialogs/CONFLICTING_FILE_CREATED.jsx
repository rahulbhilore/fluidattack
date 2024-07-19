import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import { Button } from "@material-ui/core";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import ApplicationActions from "../../../actions/ApplicationActions";

const dummyCheckFunction = () => true;
const useStyles = makeStyles(theme => ({
  body: {
    padding: theme.spacing(1)
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

export default function conflictingFileDialog({ info }) {
  const handleSubmit = () => {
    ApplicationActions.changePage(`/file/${info.conflictingFileId}`);
    ModalActions.hide();
  };

  const declineConflictingFile = () => {
    ModalActions.hide();
  };

  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="openConflictingFileForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
          checkFunction={dummyCheckFunction}
        >
          <Typography
            variant="body1"
            className={clsx(classes.text, classes.caption)}
          >
            <FormattedMessage
              id="conflictingFileCreatedOpenIt"
              values={{
                br: IntlTagValues.br,
                strong: IntlTagValues.strong,
                originalName: info.fileName,
                name: info.conflictingFileName
              }}
            />
            <br />
            <a
              href="/help/index.htm#t=ui_etc%2Ft_conflict_file.htm&rhsearch=collaboration&rhsyns=%20"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Conflicting file help"
            >
              <FormattedMessage id="conflictingFileHelp" />
            </a>
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter showCancel={false}>
        <Button
          className={classes.cancelButton}
          onClick={declineConflictingFile}
        >
          <FormattedMessage id="no" />
        </Button>
        <KudoButton
          formId="openConflictingFileForm"
          isSubmit
          isDisabled={false}
        >
          <FormattedMessage id="yes" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
