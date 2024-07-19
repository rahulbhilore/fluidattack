import React from "react";
import { FormattedMessage } from "react-intl";
import KudoPage from "../../../Page/KudoPage";
import KudoPageContent from "../../../Page/KudoPageContent";
import KudoPageFooter from "../../../Page/KudoPageFooter";
import KudoPageTitle from "../../../Page/KudoPageTitle";
import AccountDataPageContent from "./PageContent";

export default function AccountDataPage({ isMobile }: { isMobile: boolean }) {
  return (
    <KudoPage isMobile={isMobile}>
      <KudoPageTitle>
        <FormattedMessage id="accountData" />
      </KudoPageTitle>
      <KudoPageContent>
        <AccountDataPageContent />
      </KudoPageContent>
      <KudoPageFooter />
    </KudoPage>
  );
}
