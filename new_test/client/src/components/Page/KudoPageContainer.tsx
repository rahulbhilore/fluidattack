import { Box, useMediaQuery, useTheme } from "@mui/material";
import React, { ReactNode } from "react";

export default function KudoPageContainer({
  children
}: {
  children: ReactNode;
}) {
  const theme = useTheme();
  const isLargerThanMdScreen = useMediaQuery(theme.breakpoints.up("md"));
  return (
    <Box {...(isLargerThanMdScreen ? { minWidth: 630 } : {})}>{children}</Box>
  );
}
