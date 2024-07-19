import { Box } from "@mui/material";
import React, { useEffect, useState } from "react";
import { useIntl } from "react-intl";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import AccountDataPage from "./AccountData";
import PreferencesPage from "./Preferences";
import ProfilePageSidebar from "./ProfilePageSidebar";

export default function ProfilePage() {
  const intl = useIntl();
  const { pathname: currentTab } = location;
  const defaultTitle = ApplicationStore.getApplicationSetting("defaultTitle");
  const [isMobile, setIsMobile] = useState(MainFunctions.isMobileDevice());

  useEffect(() => {
    document.title = `${defaultTitle} | ${intl.formatMessage({
      id: "profile"
    })}`;
  }, []);

  return (
    <Box display="flex" sx={{ background: theme => theme.palette.bg }}>
      <ProfilePageSidebar
        isMobile={isMobile}
        onCollapseClick={() => setIsMobile(true)}
        onExpandClick={() => setIsMobile(false)}
      />
      {currentTab.includes("preferences") ? (
        <PreferencesPage isMobile={isMobile} />
      ) : (
        <AccountDataPage isMobile={isMobile} />
      )}
    </Box>
  );
}
