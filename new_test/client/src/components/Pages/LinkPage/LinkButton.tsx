import { Button, CircularProgress } from "@mui/material";
import React from "react";
import LinkSkeleton from "./LinkSkeleton";

type Props = {
  disabled: boolean;
  onClick: () => void;
  isLoading: boolean;
  variant: "primary" | "secondary";
  text: string;
  showSkeleton?: boolean;
};

export default function LinkButton({
  showSkeleton = false,
  disabled,
  isLoading,
  onClick,
  variant,
  text
}: Props) {
  if (showSkeleton) {
    return <LinkSkeleton height="100%" />;
  }

  return (
    <Button
      disabled={disabled}
      onClick={onClick}
      sx={{
        textTransform: "none !important",
        fontSize: "16px",
        fontStyle: "normal",
        fontWeight: 500,
        lineHeight: "28px",
        color: "#ffffff",

        padding: "20px 10px",
        height: "65px",
        flex: "1 1 0",
        borderRadius: "3px",
        backgroundColor: variant === "primary" ? "#3560CE" : "#485569",
        "&:hover": {
          backgroundColor: variant === "primary" ? "#4B7AF1" : "#677995"
        }
      }}
    >
      {text}
      {isLoading && (
        <CircularProgress size="25px" sx={{ color: "white", ml: 2 }} />
      )}
    </Button>
  );
}
