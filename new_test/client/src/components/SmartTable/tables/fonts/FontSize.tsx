import React from "react";
import { observer } from "mobx-react-lite";
import { Typography } from "@mui/material";
import MainFunctions from "../../../../libraries/MainFunctions";
import FontResourceFile from "../../../../stores/resources/fonts/FontResourceFile";

type Props = {
  data: FontResourceFile;
};

function FontSize({ data }: Props) {
  const { fileSize, uploadProgress } = data;

  const stop = () => {
    data.stopUpload();
  };

  if (uploadProgress)
    return (
      <Typography
        sx={{
          fontWeight: 900,
          marginLeft: "4px"
        }}
        variant="body2"
      >
        {`${uploadProgress}%`}
        <button type="button" onClick={stop}>
          X
        </button>
      </Typography>
    );

  if (fileSize)
    return (
      <Typography
        variant="body2"
        sx={{
          fontWeight: 900,
          marginLeft: "4px"
        }}
      >
        {MainFunctions.formatBytes(Number(fileSize))}
      </Typography>
    );

  return null;
}
export default observer(FontSize);
