import React from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import BlocksActions from "../../../actions/BlocksActions";
import userInfoStore from "../../../stores/UserInfoStore";
import KudoTreeView from "../../Inputs/KudoTreeView/KudoTreeView";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(() => ({
  root: {
    padding: 0
  }
}));

export default function chooseBlockLibrary() {
  const handleSubmit = formData => {
    const selectedLibraryId = formData.tree.value;
    if (selectedLibraryId) {
      XenonConnectionActions.postMessage({
        messageName: "librarySelected",
        libraryId: selectedLibraryId
      });
      ModalActions.hide();
    } else {
      SnackbarUtils.alertError({ id: "noItemSelected" });
    }
  };
  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.root}>
        <KudoForm id="chooseBlockForm" onSubmitFunction={handleSubmit}>
          <KudoTreeView
            name="tree"
            id="tree"
            label="Folder"
            formId="chooseBlockForm"
            customGetContent={() =>
              new Promise(resolve => {
                BlocksActions.getBlockLibraries(
                  userInfoStore.getUserInfo("id")
                ).then(response => {
                  resolve({ folders: response.data.results || [], files: [] });
                });
              })
            }
            customFoldCheck={() => false}
            className={classes.formGroup}
            validationFunction={(value, rowInfo) => {
              if (!rowInfo) {
                return true;
              }
              return value !== "-1";
            }}
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="chooseBlockForm">
          <FormattedMessage id="ok" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
