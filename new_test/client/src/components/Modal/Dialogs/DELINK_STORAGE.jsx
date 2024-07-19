/**
 * Created by khizh on 8/17/2016.
 */
import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import UserInfoActions from "../../../actions/UserInfoActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

const useStyles = makeStyles(() => ({
  text: {
    fontSize: ".9rem"
  }
}));

export default function delinkStorage({ info }) {
  const handleSubmit = () => {
    UserInfoActions.removeStorage(info.storage, info.id);
    ModalActions.hide();
  };
  const classes = useStyles();

  return (
    <>
      <DialogBody>
        <KudoForm onSubmitFunction={handleSubmit} id="delinkStorageRequest">
          <Typography className={classes.text}>
            <FormattedMessage id="confirmStorageDelink" />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit isDisabled={false} formId="delinkStorageRequest">
          <FormattedMessage id="Yes" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
