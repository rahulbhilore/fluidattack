import { Box, Button, styled } from "@mui/material";
import React from "react";

const StyledToggleButtonLine = styled("span")(({ theme }) => ({
  backgroundColor: theme.palette.REY,
  borderRadius: "1px",
  display: "block",
  height: 2,
  width: 16
}));

type Props = {
  onClick: () => void;
  id?: string;
};

export default function ToggleButton({ id, onClick }: Props) {
  return (
    <Button
      sx={{
        backgroundImage: "none",
        borderRadius: 0,
        height: theme => theme.kudoStyles.HEADER_HEIGHT,
        p: 0,
        position: "relative",
        minWidth: 25,
        width: 40,
        "&:focus, &:hover, &:active": {
          bgcolor: theme => theme.palette.VADER
        }
      }}
      onClick={onClick}
      data-object-name="button_ID_DISPLAYMENU"
      id={id}
    >
      <Box
        sx={{
          width: 25,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          gap: "4px"
        }}
      >
        <StyledToggleButtonLine />
        <StyledToggleButtonLine />
        <StyledToggleButtonLine />
      </Box>
    </Button>
  );
}
