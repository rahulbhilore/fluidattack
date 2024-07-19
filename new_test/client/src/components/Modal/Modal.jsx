import React, { useEffect, useState } from "react";
import Dialog from "@material-ui/core/Dialog";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Shortcuts } from "react-shortcuts";
import clsx from "clsx";
import DialogHeader from "./DialogHeader";
import ModalActions from "../../actions/ModalActions";
import ProcessActions from "../../actions/ProcessActions";
import * as ModalConstants from "../../constants/ModalConstants";
import ModalStore, { CHANGE } from "../../stores/ModalStore";

const useStyles = makeStyles(theme => ({
  paper: {
    borderRadius: 0,
    backgroundColor: theme.palette.LIGHT,
    [theme.breakpoints.down("sm")]: {
      marginLeft: "4px",
      marginRight: "4px",
      width: "calc(100% - 12px)"
    }
  },
  paperApp: {
    margin: 0,
    width: "100%"
  },
  [ModalConstants.CHOOSE_OBJECT]: {
    height: "70%"
  }
}));

const TS_DIALOGS = ["REMOVE_PERMISSION", "CHANGE_EDITOR"];

export default function Modal() {
  const classes = useStyles();
  const [dialogInfo, setInfo] = useState({
    isOpened: false,
    currentDialog: null,
    dialog: null,
    params: {}
  });

  const [caption, setCaption] = useState("");
  const [additionalCaptionParams, setCaptionParams] = useState({
    enforceTooltip: false,
    fullCaption: ""
  });

  const onClose = (event, reason) => {
    if (reason !== "backdropClick" && !location.pathname.includes("/app/")) {
      if (
        ModalStore.getCurrentInfo().currentDialog ===
        ModalConstants.CONFIRM_DELETE
      ) {
        const modalInfo = ModalStore.getCurrentInfo();
        if (modalInfo.params) {
          const { ids = {} } = modalInfo.params;
          [...(ids?.files || []), ...(ids?.folders || [])].forEach(id => {
            ProcessActions.end(id);
          });
        }
      }
      ModalActions.hide();
    }
  };

  const parseAdditionalParams = () => {
    const { enforceTooltip, fullCaption } = dialogInfo.params;
    let changeFlag = false;
    if (
      enforceTooltip &&
      additionalCaptionParams.enforceTooltip !== enforceTooltip
    ) {
      additionalCaptionParams.enforceTooltip = enforceTooltip;
      changeFlag = true;
    }
    if (fullCaption && additionalCaptionParams.fullCaption !== fullCaption) {
      additionalCaptionParams.fullCaption = fullCaption;
      changeFlag = true;
    }
    if (changeFlag) {
      setCaptionParams(additionalCaptionParams);
    }
  };

  const resetAdditionalParams = () => {
    setCaptionParams({
      enforceTooltip: false,
      fullCaption: ""
    });
  };

  const dialogChanged = () => {
    const newDialogInfo = ModalStore.getCurrentInfo();
    if (newDialogInfo.isOpened) {
      const formattedDialogName = newDialogInfo.currentDialog.substr(
        ModalConstants.constantPrefix.length
      );
      setCaption(newDialogInfo.caption);
      const extension = TS_DIALOGS.includes(formattedDialogName)
        ? "tsx"
        : "jsx";
      import(`./Dialogs/${formattedDialogName}.${extension}`).then(
        dialogComponent => {
          newDialogInfo.dialog = dialogComponent.default;
          setInfo(newDialogInfo);
          parseAdditionalParams();
        }
      );
    } else {
      newDialogInfo.dialog = null;
      setInfo(newDialogInfo);
      resetAdditionalParams();
    }
  };

  useEffect(() => {
    ModalStore.addListener(CHANGE, dialogChanged);
    return () => {
      ModalStore.removeListener(CHANGE, dialogChanged);
    };
  }, []);

  let size = "sm";
  if (dialogInfo.currentDialog === ModalConstants.VERSION_CONTROL) {
    size = "md";
  }
  if (
    dialogInfo.currentDialog === ModalConstants.SHOW_MESSAGE_INFO ||
    dialogInfo.currentDialog === ModalConstants.CLONE_OBJECT
  ) {
    size = "xs";
  }

  const isApp = location.pathname.includes("/app/");

  let fullScreen = false;
  if (dialogInfo.currentDialog === ModalConstants.SHOW_IMAGE || isApp) {
    fullScreen = true;
  }

  const onKey = action => {
    if (
      action === "CLOSE" &&
      dialogInfo.isOpened &&
      !location.pathname.includes("/app/")
    ) {
      onClose();
    }
  };
  return (
    <Shortcuts name="MODAL" handler={onKey} global targetNodeSelector="body">
      <Dialog
        onClose={onClose}
        fullWidth
        keepMounted
        fullScreen={fullScreen}
        maxWidth={size}
        open={dialogInfo.isOpened}
        disableEnforceFocus
        classes={{
          root: "dialog",
          paper: clsx(
            classes.paper,
            classes[dialogInfo.currentDialog],
            location.href.includes("/app/") ? classes.paperApp : null
          )
        }}
        data-dialog={dialogInfo.currentDialog}
        data-component="modal-block"
      >
        {dialogInfo.isOpened && dialogInfo.dialog ? (
          <>
            {isApp ? null : (
              <DialogHeader
                currentDialog={dialogInfo.currentDialog}
                caption={caption}
                onClose={onClose}
                enforceTooltip={additionalCaptionParams.enforceTooltip}
                fullCaption={additionalCaptionParams.fullCaption}
              />
            )}
            <dialogInfo.dialog
              info={dialogInfo.params}
              close={ModalActions.close}
              changeDialogCaption={setCaption}
              setCaptionParams={setCaptionParams}
            />
          </>
        ) : null}
      </Dialog>
    </Shortcuts>
  );
}
