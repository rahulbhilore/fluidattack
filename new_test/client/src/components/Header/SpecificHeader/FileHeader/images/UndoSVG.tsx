import React from "react";
import { useTheme } from "@mui/material";

type Props = {
  enabled: boolean;
};

const UndoSVG = ({ enabled }: Props) => {
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
        d="M10 6H6V9L2 5L6 1V4H10C12.2 4 14 5.8 14 8V10C14 12.2 12.2 14 10 14H6V12H10C11.1 12 12 11.1 12 10V8C12 6.9 11.1 6 10 6Z"
        fill={color}
      />
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M6 9V6V4V1L2 5L6 9Z"
        fill={color}
      />
    </svg>
  );
};

export default UndoSVG;
