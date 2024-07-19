import {
  Button,
  ClickAwayListener,
  Grid,
  IconButton,
  Stack,
  Tooltip
} from "@mui/material";
import React, { useCallback, useContext } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { ReactSVG } from "react-svg";
import infoIconSVG from "../../../../../assets/images/dialogs/icons/infoIconSVG.svg";
import { PermissionsDialogContext } from "../PermissionsDialogContext";
import SectionHeader from "../SectionHeader";
import MainFunctions from "../../../../../libraries/MainFunctions";

export default function NoPublicFileExists() {
  const [openTooltip, setOpenTooltip] = React.useState(false);
  const {
    isMobile,
    publicAccess: { createPublicLink }
  } = useContext(PermissionsDialogContext);
  const { formatMessage } = useIntl();

  const handleTooltipClose = useCallback(() => {
    setOpenTooltip(false);
  }, []);

  const handleTooltipOpen = useCallback(() => {
    setOpenTooltip(true);
  }, []);
  const isMobileDevice = MainFunctions.isMobileDevice();
  return (
    <Grid container rowGap={1}>
      <Grid xs={12} display="flex" alignItems="center" item>
        <SectionHeader header={formatMessage({ id: "noPublicFileCreated" })} />
      </Grid>
      <Grid xs={12} item>
        <Stack
          direction="row"
          alignItems="center"
          columnGap={1}
          // justifyContent={isMobile ? "flex-end" : "flex-start"}
        >
          <Button
            variant="contained"
            data-component="create-public-link-button"
            onClick={createPublicLink}
            sx={{ flex: 1 }}
          >
            <FormattedMessage
              id={isMobile ? "createLink" : "createPublicLink"}
            />
          </Button>

          {isMobileDevice ? (
            <ClickAwayListener onClickAway={handleTooltipClose}>
              <div>
                <Tooltip
                  PopperProps={{
                    disablePortal: true
                  }}
                  onClose={handleTooltipClose}
                  open={openTooltip}
                  disableFocusListener
                  disableHoverListener
                  disableTouchListener
                  title={formatMessage({ id: "createPublicFileTooltip" })}
                  placement="right-end"
                  arrow
                >
                  <IconButton
                    onClick={handleTooltipOpen}
                    disableRipple
                    sx={{
                      color: ({ palette }) =>
                        palette.button.secondary.contained.textColor,
                      "& .react-svg-icon > div": {
                        display: "flex"
                      }
                    }}
                  >
                    <ReactSVG className="react-svg-icon" src={infoIconSVG} />
                  </IconButton>
                </Tooltip>
              </div>
            </ClickAwayListener>
          ) : (
            <Tooltip
              title={formatMessage({ id: "createPublicFileTooltip" })}
              placement="right-end"
              arrow
            >
              <IconButton
                disableRipple
                sx={{
                  color: ({ palette }) =>
                    palette.button.secondary.contained.textColor,
                  "& .react-svg-icon > div": {
                    display: "flex"
                  }
                }}
              >
                <ReactSVG className="react-svg-icon" src={infoIconSVG} />
              </IconButton>
            </Tooltip>
          )}
        </Stack>
      </Grid>
    </Grid>
  );
}
