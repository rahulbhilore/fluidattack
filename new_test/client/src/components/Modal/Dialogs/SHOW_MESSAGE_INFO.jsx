import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import { Box, Typography } from "@material-ui/core";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import KudoButton from "../../Inputs/KudoButton/KudoButton";

const useStyles = makeStyles(() => ({
  typo: {
    textAlign: "center"
  },
  buttonBlock: {
    width: "100%",
    textAlign: "center"
  }
}));

export default function ShowMessageInfo({ info }) {
  const classes = useStyles();
  const handleSubmit = () => {
    if (info.onSubmit && typeof info.onSubmit === "function") {
      info.onSubmit();
    }
    ModalActions.hide();
  };

  return (
    <>
      <DialogBody>
        <Typography className={classes.typo}>{info.message}</Typography>
      </DialogBody>
      <DialogFooter showCancel={false}>
        <Box className={classes.buttonBlock}>
          <KudoButton
            isSubmit={false}
            isDisabled={false}
            onClick={handleSubmit}
          >
            <FormattedMessage id="ok" />
          </KudoButton>
        </Box>
      </DialogFooter>
    </>
  );
}

ShowMessageInfo.propTypes = {
  info: PropTypes.shape({
    message: PropTypes.string.isRequired,
    onSubmit: PropTypes.func
  }).isRequired
};
