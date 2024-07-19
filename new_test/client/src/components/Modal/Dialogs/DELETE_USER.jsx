import React, { useEffect } from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import ApplicationStore from "../../../stores/ApplicationStore";
import AdminActions from "../../../actions/AdminActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

const useStyles = makeStyles(theme => ({
  caption: {
    fontSize: theme.typography.pxToRem(14),
    textAlign: "center",
    marginBottom: theme.spacing(1),
    color: theme.palette.DARK
  }
}));

export default function deleteUser({ info }) {
  const handleSubmit = () => {
    if (info.deleteId) {
      AdminActions.deleteUserCheck(info.userId, info.deleteId);
    } else {
      AdminActions.deleteUser(info.userId);
    }
    ModalActions.hide();
  };
  useEffect(() => {
    const submitButton = document.querySelector(
      ".dialog button[type='submit']"
    );
    if (submitButton) submitButton.focus();
  }, [info]);
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <KudoForm
          id="deleteUserForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
        >
          <Typography variant="body1" className={classes.caption}>
            <FormattedMessage
              id="userDeleteConfirmation"
              values={{
                product: ApplicationStore.getApplicationSetting("product")
              }}
            />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isDisabled={false} isSubmit formId="deleteUserForm">
          <FormattedMessage id="Yes" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
