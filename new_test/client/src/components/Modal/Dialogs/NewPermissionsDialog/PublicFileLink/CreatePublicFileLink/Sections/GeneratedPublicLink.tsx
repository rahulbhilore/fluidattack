import { Grid, InputBase, Stack, Typography, styled } from "@mui/material";
import React, { isValidElement, useCallback, useContext } from "react";
import { FormattedMessage } from "react-intl";
import { ReactSVG } from "react-svg";
import copyContentSVG from "../../../../../../../assets/images/dialogs/icons/copyContentIconSVG.svg";
import deleteSVG from "../../../../../../../assets/images/dialogs/icons/deleteIconSVG.svg";
import * as IntlTagValues from "../../../../../../../constants/appConstants/IntlTagValues";
import SnackbarUtils from "../../../../../../Notifications/Snackbars/SnackController";
import { PermissionsDialogContext } from "../../../PermissionsDialogContext";
import ResponsiveButton from "../../ResponsiveButton";

const StyledPublicLinkInput = styled(InputBase)(({ theme }) => ({
  border: `1px solid ${theme.palette.REY}`,
  color: theme.palette.DARK,
  fontSize: 12,
  padding: "2px 10px",
  flex: 1,
  height: "35px"
}));

export default function GeneratedPublicLink() {
  const {
    publicAccess: {
      generatedLink,
      link,
      isPublic,
      isViewOnly,
      endTime,
      deletePublicLink
    }
  } = useContext(PermissionsDialogContext);

  const onCopyClick = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(generatedLink as string);
      SnackbarUtils.alertInfo({ id: "copiedToClipboard" });
    } catch (error) {
      console.error(`[COPY] Couldn't copy to clipboard`, error);
      SnackbarUtils.alertError({ id: "cannotCopyToClipboard" });
    }
  }, [generatedLink]);

  return (
    <Grid container>
      {!isPublic && !isViewOnly && (
        <Grid item xs={12}>
          <FormattedMessage
            id="permissionsDisabledLinkInfo"
            values={{
              strong: IntlTagValues.strong,
              br: IntlTagValues.br
            }}
          />
        </Grid>
      )}
      <Grid item xs={12}>
        {isValidElement(link) ? (
          <Typography variant="h3">{link}</Typography>
        ) : (
          <Stack direction="row" columnGap={1}>
            <StyledPublicLinkInput
              aria-readonly
              readOnly
              value={generatedLink}
              disabled={
                (!isPublic && !isViewOnly) ||
                (!!endTime && endTime < Date.now())
              }
              data-component="publicLinkInput"
            />

            <ResponsiveButton
              variant="contained"
              onClick={onCopyClick}
              sx={{
                "& .react-svg-icon > div": { display: "flex" }
              }}
              icon={
                <ReactSVG src={copyContentSVG} className="react-svg-icon" />
              }
              data-component="copy-button"
            >
              <FormattedMessage id="copy" />
            </ResponsiveButton>
            <ResponsiveButton
              variant="contained"
              color="error"
              onClick={deletePublicLink}
              sx={{
                "& .react-svg-icon > div": { display: "flex" }
              }}
              icon={<ReactSVG src={deleteSVG} className="react-svg-icon" />}
              data-component="delete-button"
            >
              <FormattedMessage id="deleteLink" />
            </ResponsiveButton>
          </Stack>
        )}
      </Grid>
    </Grid>
  );
}
