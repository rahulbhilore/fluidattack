import { MenuItem, Typography } from "@mui/material";
import React, { ReactNode } from "react";

type PropType = {
  onClick: () => void;
  children: ReactNode;
};

export default function LoginMenuItem({ onClick, children }: PropType) {
  return (
    <MenuItem
      onClick={onClick}
      sx={{
        borderRadius: 0,
        height: 36,
        minHeight: 36,
        padding: "3px 6px",
        textTransform: "initial",
        fontSize: ".75rem",
        "&:hover": {
          backgroundColor: theme => `${theme.palette.OBI}!important`
        }
      }}
      data-component="login_control_menu"
    >
      <Typography
        variant="caption"
        sx={{
          color: theme => theme.palette.REY,
          marginLeft: "10px",
          marginBottom: "2px"
        }}
      >
        {children}
      </Typography>
    </MenuItem>
  );
}
