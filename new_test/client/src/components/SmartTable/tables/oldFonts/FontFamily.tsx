import React from "react";
import Typography from "@mui/material/Typography";

type FontFamilyProps = {
  fontFamily: string;
};

function FontFamily({ fontFamily }: FontFamilyProps) {
  return (
    <Typography sx={{ ml: 2 }} variant="body2">
      {fontFamily || String.fromCharCode(8212)}
    </Typography>
  );
}

export default React.memo(FontFamily);
