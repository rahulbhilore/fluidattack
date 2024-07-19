import { Box, IconButton, Stack, Tooltip, Typography } from "@mui/material";
import InfoIcon from "@mui/icons-material/Info";
import React, { useEffect, useMemo, useState } from "react";
import { Controller, useFormContext } from "react-hook-form";
import { FormattedMessage, useIntl } from "react-intl";
import MainFunctions from "../../../../libraries/MainFunctions";
import UserInfoStore, { INFO_UPDATE } from "../../../../stores/UserInfoStore";
import KudoRadio from "../../../Inputs/KudoRadioNext/KudoRadio";
import KudoSelect from "../../../Inputs/KudoSelectNext/KudoSelect";
import KudoSwitch from "../../../Inputs/KudoSwitchNext/KudoSwitch";
import Loader from "../../../Loader";
import { PreferencesValues } from "../types";
import useTranslate from "../../../../hooks/useTranslate";

export default function PreferencesForm() {
  const [isLoaded, setIsLoaded] = useState(
    UserInfoStore.getUserInfo("isFullInfo")
  );
  const { formatMessage } = useIntl();
  const { control, setValue } = useFormContext<PreferencesValues>();
  const { t } = useTranslate();

  const appToUseDesktopOptions = useMemo(
    () => ({
      AK: formatMessage({ id: "openInKudo" }),
      AC: formatMessage({
        id: MainFunctions.isMobileDevice() ? "openInTouch" : "openInCommander"
      }),
      ask: formatMessage({ id: "askEveryTime" })
    }),
    []
  );

  const frequencyOfGettingEmailNotificationsOptions = useMemo<
    Array<{ label: string; value: string }>
  >(
    () => [
      {
        value: "Immediately_on_change",
        label: formatMessage({ id: "immediatelyOnChange" })
      },
      { value: "Hourly", label: formatMessage({ id: "hourly" }) },
      { value: "Daily", label: formatMessage({ id: "daily" }) },
      { value: "Never", label: formatMessage({ id: "never" }) }
    ],
    []
  );

  const onUserInfoUpdate = () => {
    const {
      appToUseDesktop,
      frequencyOfGettingEmailNotifications,
      receiveAdminEmails,
      showPictureRecordingTags,
      showVoiceRecordingTranscription,
      useOldUI
    } = UserInfoStore.getUserInfo("preferences") ?? {};

    setValue("appToUseDesktop", appToUseDesktop);
    setValue(
      "frequencyOfGettingEmailNotifications",
      frequencyOfGettingEmailNotifications ?? "Hourly"
    );
    setValue("receiveAdminEmails", Boolean(receiveAdminEmails));
    setValue("showPictureRecordingTags", Boolean(showPictureRecordingTags));
    setValue(
      "showVoiceRecordingTranscription",
      Boolean(showVoiceRecordingTranscription)
    );
    setValue("useOldUI", Boolean(useOldUI));

    setIsLoaded(UserInfoStore.getUserInfo("isFullInfo"));
  };

  useEffect(() => {
    UserInfoStore.addChangeListener(INFO_UPDATE, onUserInfoUpdate);
    MainFunctions.updateBodyClasses([], ["init"]);
    onUserInfoUpdate();

    return () => {
      UserInfoStore.removeChangeListener(INFO_UPDATE, onUserInfoUpdate);
    };
  }, []);

  if (!isLoaded) return <Loader />;
  return (
    <Stack sx={{ rowGap: 4, pb: 4 }}>
      <Controller
        control={control}
        name="appToUseDesktop"
        render={({ field: { ref, ...rest } }) => (
          <KudoSelect
            dataComponent="app-to-use-desktop-select"
            label="defaultAppForOpeningDrawings"
            options={appToUseDesktopOptions}
            {...rest}
          />
        )}
      />

      <Controller
        control={control}
        name="frequencyOfGettingEmailNotifications"
        render={({ field: { ref, ...rest } }) => (
          <KudoRadio
            dataComponent="notifications-radio"
            label="frequencyOfGettingEmailNotifications"
            options={frequencyOfGettingEmailNotificationsOptions}
            {...rest}
          />
        )}
      />

      <Stack rowGap={2.5}>
        <Typography
          variant="body1"
          sx={{
            color: theme => theme.palette.header,
            fontSize: 14,
            lineHeight: 2,
            fontWeight: 600
          }}
        >
          <FormattedMessage id="CommentingSettings" />
        </Typography>

        <Controller
          control={control}
          name="showVoiceRecordingTranscription"
          render={({ field: { onBlur, onChange, value } }) => (
            <KudoSwitch
              checked={value}
              dataComponent="voice-record-switch"
              fullWidth
              id="showVoiceRecordingTranscription"
              label="createVoiceRecordingTranscription"
              labelPlacement="start"
              onBlur={onBlur}
              onChange={onChange}
              translateLabel
            />
          )}
        />
        <Controller
          control={control}
          name="showPictureRecordingTags"
          render={({ field: { onBlur, onChange, value } }) => (
            <KudoSwitch
              checked={value}
              dataComponent="show-pictures-switch"
              fullWidth
              id="showPictureRecordingTags"
              label="createPictureRecordingTags"
              labelPlacement="start"
              onBlur={onBlur}
              onChange={onChange}
              translateLabel
            />
          )}
        />
        {UserInfoStore.getUserInfo("isAdmin") && (
          <Controller
            control={control}
            name="receiveAdminEmails"
            render={({ field: { onBlur, onChange, value } }) => (
              <KudoSwitch
                checked={value}
                dataComponent="receivie-email-switch"
                fullWidth
                id="receiveAdminEmails"
                label="createReceiveRelatedEmails"
                labelPlacement="start"
                onBlur={onBlur}
                onChange={onChange}
                translateLabel
              />
            )}
          />
        )}
      </Stack>
      <Stack rowGap={2.5}>
        <Typography
          variant="body1"
          sx={{
            color: theme => theme.palette.header,
            fontSize: 14,
            lineHeight: 2,
            fontWeight: 600
          }}
        >
          <FormattedMessage id="preferences.editor.drawingEditorLayout" />
        </Typography>
        <Controller
          control={control}
          name="useOldUI"
          render={({ field: { onBlur, onChange, value } }) => (
            <KudoSwitch
              checked={value}
              dataComponent="use-old-ui-switch"
              fullWidth
              id="useOldUI"
              label="preferences.editor.useClassicMode"
              customLabelComponent={
                <Box
                  sx={{
                    display: "flex",
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 1
                  }}
                >
                  <FormattedMessage id="preferences.editor.useClassicMode" />
                  <Tooltip
                    placement="top"
                    title={t(
                      "preferences.editor.toggleSettingToSwitchBetweenClassicAndRibbonMode"
                    )}
                  >
                    <IconButton>
                      <InfoIcon color="primary" />
                    </IconButton>
                  </Tooltip>
                </Box>
              }
              labelPlacement="start"
              onBlur={onBlur}
              onChange={onChange}
              translateLabel
            />
          )}
        />
      </Stack>
    </Stack>
  );
}
