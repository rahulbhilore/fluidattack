import React from "react";
import { useTheme } from "@mui/material";

type Props = {
  enabled: boolean;
};

const RedoSVG = ({ enabled }: Props) => {
  const theme = useTheme();

  const color = enabled
    ? theme.palette.quickAccess.activeIcon
    : theme.palette.JANGO;

  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M6 6H10V9L14 5L10 1V4H6C3.8 4 2 5.8 2 8V10C2 12.2 3.8 14 6 14H10V12H6C4.9 12 4 11.1 4 10V8C4 6.9 4.9 6 6 6Z"
        fill={color}
      />
    </svg>
  );
};

export default RedoSVG;
