import { Stack } from "@mui/material";
import React, { SyntheticEvent } from "react";
import { useFormContext } from "react-hook-form";
import { FormattedMessage } from "react-intl";
import UserInfoActions from "../../../../actions/UserInfoActions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import KudoButton from "../../../Inputs/KudoButtonNext/KudoButton";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";
import { PreferencesValues } from "../types";

export default function PageFooter() {
  const { handleSubmit, reset } = useFormContext<PreferencesValues>();

  const handleFormSubmit = (e: SyntheticEvent) => {
    handleSubmit(values => {
      const {
        frequencyOfGettingEmailNotifications,
        showPictureRecordingTags,
        showVoiceRecordingTranscription,
        appToUseDesktop,
        receiveAdminEmails,
        useOldUI
      } = values;

      const modificationData: { preferences: Partial<PreferencesValues> } = {
        preferences: UserInfoStore.getUserInfo("preferences")
      };

      modificationData.preferences.appToUseDesktop = appToUseDesktop;

      modificationData.preferences.frequencyOfGettingEmailNotifications =
        frequencyOfGettingEmailNotifications;

      modificationData.preferences.showVoiceRecordingTranscription =
        showVoiceRecordingTranscription;

      modificationData.preferences.showPictureRecordingTags =
        showPictureRecordingTags;

      if (UserInfoStore.getUserInfo("isAdmin")) {
        modificationData.preferences.receiveAdminEmails = receiveAdminEmails;
      }
      modificationData.preferences.useOldUI = useOldUI;

      UserInfoActions.modifyUserInfo(modificationData).then(() => {
        SnackbarUtils.alertInfo({ id: "changesSaved" });
      });
    })(e);
  };

  return (
    <Stack direction="row" justifyContent="flex-end" columnGap={2.5}>
      <KudoButton
        data-component="resetPreferences"
        color="secondary"
        type="reset"
        onClick={() => reset()}
      >
        <FormattedMessage id="resetToDefault" />
      </KudoButton>
      <KudoButton id="prefSaveButton" type="submit" onClick={handleFormSubmit}>
        <FormattedMessage id="savePreferences" />
      </KudoButton>
    </Stack>
  );
}
