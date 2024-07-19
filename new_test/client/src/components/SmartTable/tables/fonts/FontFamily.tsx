import React from "react";
import { observer } from "mobx-react-lite";
import Typography from "@mui/material/Typography";
import { OBJECT_TYPES } from "../../../../stores/resources/BaseResourcesStore";
import FontResourceFile from "../../../../stores/resources/fonts/FontResourceFile";
import ResourceFolder from "../../../../stores/resources/ResourceFolder";

type Props = {
  data: FontResourceFile | ResourceFolder;
};

function FontFamily({ data }: Props) {
  const { id: _id, type } = data;

  if (type === OBJECT_TYPES.FOLDER) return null;

  let fontFamily = null;

  if (data instanceof FontResourceFile) fontFamily = data?.fontFamity;

  return (
    <Typography sx={{ ml: 2 }} variant="body2">
      {fontFamily || String.fromCharCode(8212)}
    </Typography>
  );
}

export default observer(FontFamily);
