import React from "react";
import { Typography, Grid } from "@mui/material";
import { useTheme } from "@material-ui/core";
import { FormattedMessage } from "react-intl";
import { Link } from "react-router";
import storageConnectionLostIcon from "../../../assets/images/icons/storageConnectionLost.svg";

export default function ReconnectMessage() {
  const renderLinkToStorages = React.useCallback(
    (txt: string) => <Link to="/storages">{txt}</Link>,
    []
  );
  const theme = useTheme();

  return (
    <Grid
      container
      spacing={0}
      direction="column"
      alignItems="center"
      justifyContent="center"
      sx={{
        minHeight: "calc(100vh - 170px - 50px)",
        textAlign: "center"
      }}
    >
      <Grid item xs={12} sm={6} md={4} lg={3}>
        <img
          src={storageConnectionLostIcon}
          alt="Storage is no longer connected"
        />
        <Typography
          sx={{
            fontSize: theme.typography.pxToRem(16),
            // @ts-ignore
            color: theme.palette.JANGO
          }}
        >
          <FormattedMessage id="yourStorageIsNoLongerConnected" />
        </Typography>
        <Typography
          sx={{
            // @ts-ignore
            color: theme.palette.JANGO,
            "&, & > a": {
              fontSize: theme.typography.pxToRem(14)
            }
          }}
        >
          <FormattedMessage
            id="checkStoragesPageAndReconnect"
            values={{
              link: renderLinkToStorages
            }}
          />
        </Typography>
      </Grid>
    </Grid>
  );
}
