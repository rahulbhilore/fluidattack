import { Box } from "@mui/material";
import React, { ReactNode } from "react";
import KudoPageContainer from "./KudoPageContainer";

export default function KudoPageFooter({ children }: { children?: ReactNode }) {
  return (
    <Box
      id="page-footer"
      sx={{
        height: theme => theme.spacing(7),
        paddingTop: 2.5,
        display: "flex",
        justifyContent: "center"
      }}
    >
      <KudoPageContainer>{children}</KudoPageContainer>
    </Box>
  );
}
