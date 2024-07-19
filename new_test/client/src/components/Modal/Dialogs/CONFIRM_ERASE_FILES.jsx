import React, { useState } from "react";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import UserInfoStore from "../../../stores/UserInfoStore";

const useStyles = makeStyles(() => ({
  text: {
    fontSize: ".9rem"
  }
}));

export default function emptyTrashDialog() {
  const classes = useStyles();

  const [erasing, setErasing] = useState(false);
  const currentFilter = UserInfoStore.getUserInfo("fileFilter");

  const handleSubmit = () => {
    if (!erasing) {
      setErasing(true);

      const { accountId, storage } = FilesListStore.getCurrentFolder();
      FilesListActions.eraseFilesAction(accountId, storage)
        .then(() => {
          FilesListActions.clearTrashEntities();
        })
        .finally(() => {
          setErasing(false);
          ModalActions.hide();
        });
    }
  };

  return (
    <>
      <DialogBody>
        <KudoForm onSubmitFunction={handleSubmit} id="confirmEraseFiles">
          <Typography className={classes.text}>
            <FormattedMessage id="areYouSureCleanTrash" />
            {currentFilter !== "allFiles" ? (
              <>
                {". "}
                <FormattedMessage
                  id="filterApplied"
                  values={{
                    filterType: <FormattedMessage id={currentFilter} />
                  }}
                />
              </>
            ) : null}
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isSubmit
          isDisabled={erasing}
          isLoading={erasing}
          formId="confirmEraseFiles"
        >
          <FormattedMessage id="Yes" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
