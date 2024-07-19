import React from "react";
import { observer } from "mobx-react-lite";
import { Typography, Box } from "@mui/material";

import ResourceFile from "../../../../stores/resources/ResourceFile";

type Props = {
  data: ResourceFile;
};

function Owner({ data }: Props) {
  const { ownerName } = data;
  return (
    <Box
      sx={{
        display: "flex"
      }}
    >
      <Box>
        <Typography
          sx={{
            fontSize: theme => theme.typography.pxToRem(12),
            color: theme => theme.palette.SNOKE
          }}
          variant="body2"
          data-component="blockOwner"
          data-text={ownerName}
        >
          {ownerName}
        </Typography>
      </Box>
    </Box>
  );
}

export default observer(Owner);
