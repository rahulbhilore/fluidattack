import makeStyles from "@material-ui/core/styles/makeStyles";
import {
  Box,
  Grid,
  Typography,
  buttonClasses,
  gridClasses,
  styled,
  typographyClasses
} from "@mui/material";
import clsx from "clsx";
import React, { useContext } from "react";
import { FormattedMessage } from "react-intl";
import DialogBody from "../../DialogBody";
import InvitePeople from "./InvitePeople/InvitePeople";
import { PermissionsDialogContext } from "./PermissionsDialogContext";
import CreatePublicFileLink from "./PublicFileLink/CreatePublicFileLink/CreatePublicFileLink";
import NoPublicFileExists from "./PublicFileLink/NoPublicFileExists";

const StyledGrid = styled(Grid)(({ theme }) => ({
  [`& > .${gridClasses.root}:not(:last-child)`]: {
    borderBottom: "1px solid",
    borderColor: theme.palette.REY,
    py: 1.5
  },
  [`& .${buttonClasses.root}`]: {
    height: 35,
    fontSize: 12,
    padding: "10px 16px"
  },
  [`& .${typographyClasses.root}`]: {
    fontSize: "12px !important",
    color: `${theme.palette.DARK}`
  }
}));

const useStyles = makeStyles(theme => ({
  root: {
    padding: 0
  },
  rootApp: {
    paddingTop: `0 !important`
  },
  noPermissions: {
    padding: theme.spacing(3)
  }
}));

export default function PermissionsDialog() {
  const {
    publicAccess: { isPublicAccessAvailable, isPublic },
    isInternalAccessAvailable,
    isDrawing
  } = useContext(PermissionsDialogContext);
  const classes = useStyles();

  const cantManagePermissions =
    (!isPublicAccessAvailable || !isDrawing) && !isInternalAccessAvailable;

  return (
    <DialogBody
      className={clsx(
        classes.root,
        cantManagePermissions ? classes.noPermissions : null,
        location.href.includes("/app/") ? classes.rootApp : null
      )}
    >
      {cantManagePermissions ? (
        <Typography fontSize="13px">
          <FormattedMessage id="cantManagePermissions" />
        </Typography>
      ) : (
        <StyledGrid container rowGap={1.5}>
          {isPublicAccessAvailable && isDrawing && (
            <Grid xs={12}>
              <Box p={1.5} data-component="public-link-section">
                {isPublic ? <CreatePublicFileLink /> : <NoPublicFileExists />}
              </Box>
            </Grid>
          )}

          {isInternalAccessAvailable && (
            <Grid xs={12}>
              <Box p={1.5} data-component="sharing-section">
                <InvitePeople />
              </Box>
            </Grid>
          )}
        </StyledGrid>
      )}
    </DialogBody>
  );
}
