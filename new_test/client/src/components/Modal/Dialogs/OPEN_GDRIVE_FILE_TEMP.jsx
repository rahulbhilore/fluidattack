import React, { useState } from "react";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import UserInfoActions from "../../../actions/UserInfoActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import Loader from "../../Loader";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

const useStyles = makeStyles(theme => ({
  rightSideButton: {
    float: "right !important",
    marginLeft: theme.spacing(1)
  }
}));

export default function openTempGdriveFile() {
  const [isRedirect, setRedirect] = useState(false);
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        {isRedirect ? (
          <Loader isModal />
        ) : (
          <Typography variant="body1">
            <FormattedMessage id="loginToKudoToOpenFile" />
          </Typography>
        )}
      </DialogBody>
      {isRedirect ? null : (
        <DialogFooter>
          <KudoButton
            isDisabled={false}
            className={classes.rightSideButton}
            onClick={() => {
              setRedirect(true);
              UserInfoActions.loginWithSSO(location.pathname + location.search);
            }}
          >
            <FormattedMessage id="login" />
          </KudoButton>
        </DialogFooter>
      )}
    </>
  );
}
