import React from "react";
import { Box, Typography, keyframes } from "@mui/material";

type Props = {
  isModal?: boolean;
  isOverUI?: boolean;
  message?: string;
};
const spin = keyframes`
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
`;

export default function Loader({
  isModal = false,
  message,
  isOverUI = false
}: Props) {
  return (
    <Box
      sx={{
        position: "absolute",
        top: 0,
        left: 0,
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        height: "100%",
        width: "100%",
        zIndex: theme => theme.zIndex.appBar - 1,
        ...(isOverUI ? { zIndex: 99999 } : null),
        ...(isModal ? { position: "relative" } : null)
      }}
      data-component="loader"
    >
      <Box>
        <Box
          sx={{
            border: theme =>
              `${theme.typography.pxToRem(11)} solid rgba(255, 255, 255, 0.2)`,
            borderLeftColor: "#f6221c",
            transform: "translateZ(0)",
            animation: `${spin} 1.1s infinite linear`,
            borderRadius: "50%",
            width: "80px",
            margin: "0 auto",
            height: "80px",
            ...(isModal
              ? {
                  position: "relative",
                  width: "5em",
                  height: "5em",
                  marginTop: "60px",
                  marginBottom: "60px"
                }
              : null)
          }}
        />
        {message ? (
          <Typography
            sx={{
              marginTop: theme => theme.spacing(2),
              display: "block",
              color: theme => theme.palette.DARK,
              textAlign: "center"
            }}
          >
            {message}
          </Typography>
        ) : null}
      </Box>
    </Box>
  );
}
