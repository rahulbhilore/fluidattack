import { Button, Grid, SxProps, Theme } from "@mui/material";
import React, { useMemo, useState } from "react";
import { FormattedMessage } from "react-intl";
import ApplicationActions from "../../../actions/ApplicationActions";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import QuickTour from "../QuickTour/QuickTour";
import HelpSection from "./HelpSection";

const freeTrialClick = () => {
  ApplicationActions.changePage(
    `${ApplicationStore.getApplicationSetting("UIPrefix")}signup`
  );
};

const buyClick = () => {
  if (
    InputValidationFunctions.isURL(
      ApplicationStore.getApplicationSetting("customization").buyURL
    )
  ) {
    window.open(
      ApplicationStore.getApplicationSetting("customization").buyURL,
      "_blank",
      "noopener,noreferrer"
    );
  }
};

export default function UserNotLogged({ isMobile }: { isMobile: boolean }) {
  const [isQuickTourOpen, setQuickTourOpen] = useState(false);
  const currentPage = MainFunctions.detectPageType();
  const buttonSx = useMemo<SxProps<Theme>>(
    () => ({
      borderRadius: 0,
      border: theme => `1px solid ${theme.palette.CLONE}`,
      marginRight: "8px",
      color: theme => theme.palette.LIGHT,
      padding: theme => theme.spacing(1, "6px"),
      lineHeight: 1.5,
      "&:hover": {
        border: theme => `1px solid ${theme.palette.YELLOW_BUTTON}`,
        backgroundColor: theme => `${theme.palette.LIGHT}14`
      }
    }),
    []
  );

  return (
    <Grid container justifyContent="flex-end">
      <Grid item>
        <HelpSection isMobile={isMobile} />
        {currentPage === "index" && (
          <Button sx={{ ...buttonSx }} onClick={freeTrialClick}>
            <FormattedMessage id="freeTrial" />
          </Button>
        )}
        <Button sx={{ ...buttonSx }} onClick={buyClick}>
          <FormattedMessage id="buy" />
        </Button>
        <QuickTour
          isOpen={isQuickTourOpen}
          onClose={() => {
            setQuickTourOpen(false);
          }}
          isLoggedIn={false}
        />
        <Button
          sx={{ ...buttonSx }}
          onClick={() => {
            setQuickTourOpen(true);
          }}
        >
          <FormattedMessage id="quickTour" />
        </Button>
      </Grid>
    </Grid>
  );
}
