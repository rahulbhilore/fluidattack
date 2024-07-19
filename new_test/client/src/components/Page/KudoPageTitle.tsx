import React, { ReactNode } from "react";
import { Box, Typography } from "@mui/material";

export default function KudoPageTitle({ children }: { children: ReactNode }) {
  return (
    <Box
      id="page-title"
      display="flex"
      justifyContent="center"
      paddingBottom={2.5}
    >
      <Typography
        color={theme => theme.palette.header}
        fontWeight={600}
        fontSize={18}
      >
        {children}
      </Typography>
    </Box>
  );
}
