import LoadingButton from "@mui/lab/LoadingButton";
import { ButtonProps } from "@mui/material";
import React, { ReactNode, useContext } from "react";
import { PermissionsDialogContext } from "../PermissionsDialogContext";

type Props = {
  icon: ReactNode;
  loading?: boolean;
} & ButtonProps & {
    "data-component"?: string;
  };

export default function ResponsiveButton({
  icon,
  children,
  sx,
  ...others
}: Props) {
  const { isMobile } = useContext(PermissionsDialogContext);

  return (
    <LoadingButton
      {...others}
      sx={{
        height: 36,
        ...sx,
        ...(isMobile
          ? {
              width: 35,
              minWidth: 35
            }
          : {})
      }}
    >
      {isMobile ? icon : children}
    </LoadingButton>
  );
}
