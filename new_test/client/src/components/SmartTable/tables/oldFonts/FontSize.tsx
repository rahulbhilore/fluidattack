import React from "react";
import Typography from "@mui/material/Typography";
import MainFunctions from "../../../../libraries/MainFunctions";

type FontSizeProps = {
  size: number;
};

function FontSize({ size }: FontSizeProps) {
  return (
    <Typography sx={{ fontWeight: 900, ml: 0.5 }} variant="body2">
      {MainFunctions.formatBytes(size)}
    </Typography>
  );
}

export default React.memo(FontSize);
