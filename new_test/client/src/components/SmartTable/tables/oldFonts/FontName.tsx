import React from "react";
import { Box, Typography, styled } from "@mui/material";
import FileSVG from "../../../../assets/images/icons/file.svg";

const FontIcon = styled("img")(({ theme }) => ({
  marginLeft: theme.spacing(3),
  width: "40px"
}));
type NameProps = {
  name: string;
};

function FontName({ name }: NameProps) {
  return (
    <Box
      sx={{
        display: "flex",
        gap: 3
      }}
    >
      <Box>
        <FontIcon src={FileSVG} alt={name} />
      </Box>
      <Box
        sx={{
          display: "flex",
          alignItems: "center"
        }}
      >
        <Typography variant="body2" data-component="fontName" data-text={name}>
          {name}
        </Typography>
      </Box>
    </Box>
  );
}

export default React.memo(FontName);
