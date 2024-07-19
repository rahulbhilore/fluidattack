import { Box, SxProps } from "@mui/material";
import React, { ReactElement } from "react";

type PropType = {
  children: ReactElement | ReactElement[];
  sx?: SxProps;
};

export default function AccountInformations({ sx = {}, children }: PropType) {
  return (
    <Box
      component="div"
      flexDirection="column"
      rowGap={0.5}
      sx={{
        display: "flex",
        color: theme => theme.palette.fg,
        "& > :nth-of-type(odd)": {
          backgroundColor: theme => theme.palette.table.account.detail.standard
        },
        "& > :nth-of-type(even)": {
          backgroundColor: theme =>
            `${theme.palette.table.account.detail.standard}80`
        },
        ...sx
      }}
    >
      {children}
    </Box>
  );
}
