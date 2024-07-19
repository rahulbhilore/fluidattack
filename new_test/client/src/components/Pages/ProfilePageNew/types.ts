type FieldType = {
  name: string;
  type: string;
  valid: boolean;
  value: string | boolean;
};

type FormData<T extends string> = Record<T, FieldType>;

type FieldNames =
  | "appToUseDesktop"
  | "frequencyOfGettingEmailNotifications"
  | "receiveAdminEmails"
  | "showPictureRecordingTags"
  | "showVoiceRecordingTranscription"
  | "useOldUI";

type PreferencesValues = {
  appToUseDesktop: "ask" | "AC" | "AK";
  frequencyOfGettingEmailNotifications:
    | "Immediately_on_change"
    | "Hourly"
    | "Daily"
    | "Never";
  receiveAdminEmails: boolean;
  showPictureRecordingTags: boolean;
  showVoiceRecordingTranscription: boolean;
  useOldUI: boolean;
};

export { FormData, FieldNames, PreferencesValues };
