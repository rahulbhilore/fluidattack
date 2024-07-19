import { Box, Stack, useMediaQuery, useTheme } from "@mui/material";
import React, { ReactNode } from "react";
import HeaderSpacer from "./HeaderSpacer";

type KudoPageProps = {
  children: ReactNode;
  isMobile: boolean;
};

export default function KudoPage(props: KudoPageProps) {
  const { children, isMobile } = props;
  const theme = useTheme();
  const isSmallScreen = useMediaQuery(theme.breakpoints.down("sm"));
  const isMediumScreen = useMediaQuery(theme.breakpoints.down("md"));

  return (
    <Box
      id="page"
      component="main"
      sx={{
        height: () => `calc(100vh - ${theme.kudoStyles.HEADER_HEIGHT})`,
        width: () => {
          if (isSmallScreen)
            return `calc(100vw - ${theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN})`;
          return `calc(100vw - ${
            isMobile
              ? theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN
              : theme.kudoStyles.SIDEBAR_WIDTH
          })`;
        },
        ml: () => {
          if (isSmallScreen) return theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN;
          return isMobile
            ? theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN
            : theme.kudoStyles.SIDEBAR_WIDTH;
        },
        transition: () =>
          theme.transitions.create(["margin-left", "width"], {
            easing: theme.transitions.easing.sharp,
            duration: isMobile
              ? theme.transitions.duration.enteringScreen
              : theme.transitions.duration.leavingScreen
          })
      }}
    >
      <HeaderSpacer />

      <Stack
        height="100%"
        px={isMediumScreen ? 2 : 5}
        py={isMediumScreen ? 2 : 4}
        id="page"
      >
        {children}
      </Stack>
    </Box>
  );
}
