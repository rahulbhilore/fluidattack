import { Grid, Typography } from "@mui/material";
import React from "react";
import { FormattedMessage } from "react-intl";
import MainFunctions from "../../../../libraries/MainFunctions";

type PropType = {
  deleted?: boolean;
  deshared?: boolean;
  name: string;
  showDrawMenu?: boolean;
};

export default function MobileName({
  deleted = false,
  deshared = false,
  name,
  showDrawMenu = false
}: PropType) {
  const shrinkedName = MainFunctions.shrinkString(name, 20);
  return (
    <Grid item sx={{ width: "inherited" }}>
      <Typography
        sx={[
          {
            fontSize: 12,
            fontWeight: 900
          }
        ]}
      >
        {`${shrinkedName} `}
        {deshared && <FormattedMessage id="Deshared" />}
        {deleted && <FormattedMessage id="Deleted" />}
      </Typography>
    </Grid>
  );
}
