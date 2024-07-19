import { MenuItem, Typography } from "@mui/material";
import React, { MouseEventHandler } from "react";

type Props = {
  onClick: MouseEventHandler<HTMLLIElement>;
  caption: React.ReactElement | React.ReactNode;
  icon: string;
  dataComponent: string;
  isDisabled?: boolean;
};

const DrawingMenuItem: React.FC<Props> = ({
  onClick,
  caption,
  icon,
  dataComponent,
  isDisabled = false
}) => (
  <MenuItem
    onClick={onClick}
    sx={{
      borderRadius: 0,
      height: 36,
      minHeight: 36,
      p: 1,
      display: "flex",
      flexDirection: "row",
      gap: 1,
      "&:hover": {
        backgroundColor: theme => `${theme.palette.OBI}!important`
      }
    }}
    data-component={dataComponent}
    disabled={isDisabled}
  >
    <img
      style={{
        width: "16px",
        height: "16px",
        marginTop: 0
      }}
      src={icon}
      alt={caption?.toString()}
    />
    <Typography
      variant="caption"
      sx={{
        color: theme => theme.palette.REY
      }}
    >
      {caption}
    </Typography>
  </MenuItem>
);

export default DrawingMenuItem;
