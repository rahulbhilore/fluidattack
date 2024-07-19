import { Box } from "@mui/material";
import React, { ReactNode } from "react";

type PropType = {
  children?: ReactNode;
};
export default function HeaderSpacer({ children }: PropType) {
  return (
    <Box sx={{ width: "100%", pt: theme => theme.kudoStyles.HEADER_HEIGHT }}>
      {children}
    </Box>
  );
}
