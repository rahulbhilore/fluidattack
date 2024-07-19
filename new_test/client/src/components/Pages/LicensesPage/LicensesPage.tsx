import { Grid, Tabs, Tab, tabsClasses, tabClasses } from "@mui/material";
import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import LicensesText from "../../../license.html?raw";
import ServerLicensesText from "../../../serverlicense.html?raw";
import ApplicationActions from "../../../actions/ApplicationActions";
import ToolbarSpacer from "../../ToolbarSpacer";
import applicationStore from "../../../stores/ApplicationStore";

type LicensesTypes = "ui" | "server" | "xeClient" | "xeServer";
type Props = {
  params: {
    type: LicensesTypes;
  };
};

export default function LicensesPage({ params }: Props) {
  const [mode, setMode] = useState(params.type || "ui");
  const [xeServerLicenses, setXeServer] = useState<string | null>(null);
  const [xeClientLicenses, setXeClient] = useState<string | null>(null);
  const handleChange = (
    event: React.SyntheticEvent<Element, Event>,
    newMode: LicensesTypes
  ) => {
    setMode(newMode);
    ApplicationActions.changePage(`/licenses/${newMode}`);
  };
  useEffect(() => {
    if (params.type !== mode) {
      setMode(params.type || "ui");
    }
  }, [params.type]);
  useEffect(() => {
    document.title = `${applicationStore.getApplicationSetting(
      "defaultTitle"
    )} | licenses`;
    fetch(
      `${applicationStore.getApplicationSetting(
        "xeLicensesBase"
      )}/copyright_server.html`
    )
      .then(response => response.text())
      .then(setXeServer)
      .catch(err => {
        // eslint-disable-next-line no-console
        console.error(`Failed to load xeServer licenses: ${err}`);
      });
    fetch(
      `${applicationStore.getApplicationSetting(
        "xeLicensesBase"
      )}/copyright_client.html`
    )
      .then(response => response.text())
      .then(setXeClient)
      .catch(err => {
        // eslint-disable-next-line no-console
        console.error(`Failed to load xeClient licenses: ${err}`);
      });
  }, []);

  let htmlContent = LicensesText;
  switch (mode) {
    case "server":
      htmlContent = ServerLicensesText;
      break;
    case "xeServer":
      htmlContent = xeServerLicenses || "";
      break;
    case "xeClient":
      htmlContent = xeClientLicenses || "";
      break;
    case "ui":
    default:
      htmlContent = LicensesText;
      break;
  }
  return (
    <main style={{ flexGrow: 1, overflowY: "auto", overflowX: "hidden" }}>
      <Grid
        container
        justifyContent="center"
        spacing={2}
        sx={{
          bgcolor: theme => theme.palette.VADER,
          overflowY: "auto"
        }}
      >
        <ToolbarSpacer />
        <Grid item xs={12}>
          <Tabs
            sx={{
              [`& .${tabsClasses.indicator}`]: {
                bgcolor: theme => theme.palette.LIGHT
              },
              [`& .${tabClasses.root}`]: {
                [`&, &.${tabClasses.selected}`]: {
                  color: theme => theme.palette.LIGHT
                }
              }
            }}
            value={mode}
            onChange={handleChange}
            centered
          >
            <Tab data-component="UITab" label="UI" value="ui" />
            <Tab data-component="ServerTab" label="Server" value="server" />
            {xeServerLicenses != null ? (
              <Tab
                data-component="EditorServerTab"
                label="Editor server"
                value="xeServer"
              />
            ) : null}
            {xeClientLicenses != null ? (
              <Tab
                data-component="EditorClientTab"
                label="Editor client"
                value="xeClient"
              />
            ) : null}
          </Tabs>
        </Grid>
        <Grid
          item
          sx={{
            p: 2,
            color: theme => theme.palette.LIGHT,
            userSelect: "text",
            "& h2": {
              fontSize: "1.6rem",
              margin: theme => theme.spacing(2, 0),
              textAlign: "center"
            },
            "& p": {
              textAlign: "justify"
            }
          }}
          xs={12}
          md={8}
          lg={6}
          // eslint-disable-next-line react/no-danger
          dangerouslySetInnerHTML={{
            __html: htmlContent
          }}
        />
      </Grid>
    </main>
  );
}

LicensesPage.propTypes = {
  params: PropTypes.shape({
    type: PropTypes.oneOf(["ui", "server", "xeClient", "xeServer"])
  })
};
LicensesPage.defaultProps = {
  params: { type: "ui" }
};
