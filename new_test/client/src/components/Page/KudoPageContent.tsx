import React, { ReactNode } from "react";
import { Box } from "@mui/material";
import KudoPageContainer from "./KudoPageContainer";

export default function KudoPageContent({ children }: { children: ReactNode }) {
  return (
    <Box
      id="page-content"
      flex={1}
      sx={{
        borderBottom: "1px solid",
        borderTop: "1px solid",
        borderColor: theme => theme.palette.divider,
        display: "flex",
        maxHeight: theme => `calc(100vh - ${theme.kudoStyles.HEADER_HEIGHT})`,
        justifyContent: "center",
        overflow: "auto",
        paddingTop: 4
      }}
    >
      <KudoPageContainer>{children}</KudoPageContainer>
    </Box>
  );
}
