import React from "react";
import { FormattedMessage } from "react-intl";
import { SxProps, Typography } from "@mui/material";

type Props = {
  isSmall?: boolean;
  vendor: string;
  sx?: SxProps;
};

export default function Copyright({ isSmall, vendor, sx }: Props) {
  return (
    <Typography
      variant="body1"
      sx={{
        display: "block",
        fontSize: theme => theme.typography.pxToRem(11),
        color: theme => theme.palette.REY,
        fontWeight: "bold",
        textAlign: "center",
        lineHeight: "15px",
        ...sx
      }}
    >
      <FormattedMessage
        id={`copyright${isSmall ? "Small" : ""}`}
        values={{ vendor, year: new Date().getFullYear() }}
      />
    </Typography>
  );
}
