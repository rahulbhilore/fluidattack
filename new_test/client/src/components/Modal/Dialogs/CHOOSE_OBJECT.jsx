import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import XrefTree from "../../Xrefs/XrefTree";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import UserInfoStore from "../../../stores/UserInfoStore";
import Loader from "../../Loader";
import UserInfoActions from "../../../actions/UserInfoActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(() => ({
  root: {
    padding: 0
  }
}));

let fileFilter = null;

export default function chooseDialog({ info }) {
  const [isLoader, setLoader] = useState(false);

  useEffect(() => {
    fileFilter = UserInfoStore.getUserInfo("fileFilter");

    if (fileFilter === "allFiles") {
      setLoader(false);
      fileFilter = "allFiles";
    } else {
      UserInfoActions.modifyUserInfo({ fileFilter: "allFiles" }).then(() => {
        setLoader(false);
      });
    }
    return () => {
      if (fileFilter !== "allFiles")
        UserInfoActions.modifyUserInfo({ fileFilter }).then(() => {
          setLoader(false);
        });
    };
  }, []);

  const handleSubmit = formData => {
    const { commandType, attachmentType, wtObjectId } = info;
    const xrefData = formData.xrefTreeView.value;
    if (xrefData) {
      if (xrefData.path.length > 0) {
        xrefData.elemId = wtObjectId;
        XenonConnectionActions.postMessage({
          messageName: commandType,
          type: attachmentType,
          fileInfo: xrefData
        });
        ModalActions.hide();
      } else {
        SnackbarUtils.alertError({ id: "internalError" });
      }
    } else {
      SnackbarUtils.alertError({ id: "noItemSelected" });
    }
  };
  const { commandType, filter } = info;

  const classes = useStyles();

  if (isLoader) return <Loader isModal />;

  return (
    <>
      <DialogBody className={classes.root}>
        <KudoForm id="chooseObjectForm" onSubmitFunction={handleSubmit}>
          <XrefTree
            formId="chooseObjectForm"
            id="xrefTreeView"
            name="xrefTreeView"
            filter={filter}
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="chooseObjectForm">
          <FormattedMessage id={commandType === "import" ? "ok" : "attach"} />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
