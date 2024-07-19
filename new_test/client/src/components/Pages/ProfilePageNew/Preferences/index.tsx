import React from "react";
import { FormProvider, useForm } from "react-hook-form";
import { FormattedMessage } from "react-intl";
import KudoPage from "../../../Page/KudoPage";
import KudoPageContent from "../../../Page/KudoPageContent";
import KudoPageFooter from "../../../Page/KudoPageFooter";
import KudoPageTitle from "../../../Page/KudoPageTitle";
import { PreferencesValues } from "../types";
import PreferencesPageContent from "./PageContent";
import PreferencesPageFooter from "./PageFooter";

export default function PreferencesPage({ isMobile }: { isMobile: boolean }) {
  const formReturn = useForm<PreferencesValues>({
    defaultValues: {
      appToUseDesktop: "ask",
      frequencyOfGettingEmailNotifications: "Hourly",
      receiveAdminEmails: false,
      showPictureRecordingTags: false,
      showVoiceRecordingTranscription: false,
      useOldUI: false
    }
  });
  return (
    <FormProvider {...formReturn}>
      <KudoPage isMobile={isMobile}>
        <KudoPageTitle>
          <FormattedMessage id="Preferences" />
        </KudoPageTitle>
        <KudoPageContent>
          <PreferencesPageContent />
        </KudoPageContent>
        <KudoPageFooter>
          <PreferencesPageFooter />
        </KudoPageFooter>
      </KudoPage>
    </FormProvider>
  );
}
