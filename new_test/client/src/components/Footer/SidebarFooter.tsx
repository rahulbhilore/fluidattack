import React from "react";
import Box from "@mui/material/Box";
import { useTheme } from "@mui/material";
import Revision from "./Revision";
import Copyright from "./Copyright";
import TermsOfUse from "./links/TermsOfUse";
import BasicLink from "./links/BasicLink";

type Props = {
  customization: {
    termsOfUse: string;
    showEULAInsteadOfPP: boolean;
    showEULA: boolean;
    privacyPolicyLink: string;
    EULALink: string;
  };
  vendor: string;
};

export default function SidebarFooter({ customization, vendor }: Props) {
  const {
    termsOfUse,
    showEULAInsteadOfPP,
    showEULA,
    privacyPolicyLink,
    EULALink
  } = customization;
  const theme = useTheme();
  return (
    <Box
      component="footer"
      sx={{
        width: theme.kudoStyles.SIDEBAR_WIDTH,
        maxWidth: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MAX,
        margin: 0,
        backgroundColor: theme.palette.VADER,
        borderTopColor: theme.palette.VADER,
        padding: "18px 0",
        display: "block",
        bottom: 0,
        left: 0,
        position: "fixed",
        maxHeight: 130,
        zIndex: theme.zIndex.drawer + 5,
        [theme.breakpoints.down("xs")]: {
          width: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH
        }
      }}
    >
      <Copyright vendor={vendor} isSmall />
      <Box
        sx={{
          margin: "5px 0",
          padding: "0 10px",
          width: "100%",
          textAlign: "center",
          lineHeight: "15px",
          marginTop: "5px"
        }}
      >
        {showEULAInsteadOfPP ? (
          <>
            <TermsOfUse link={termsOfUse} />
            <br />
            <BasicLink href={EULALink} messageId="EULA" />
          </>
        ) : (
          <>
            <TermsOfUse link={termsOfUse} />
            <br />
            <BasicLink
              dataComponent="privacyPolicyLink"
              href={privacyPolicyLink}
              messageId="privacyPolicy"
            />
          </>
        )}
        {showEULA ? (
          <>
            <br />
            <BasicLink href={EULALink} messageId="EULA" />
          </>
        ) : null}
      </Box>
      <Revision isSidebar />
    </Box>
  );
}
