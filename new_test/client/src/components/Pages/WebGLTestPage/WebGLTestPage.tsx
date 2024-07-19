import { styled } from "@mui/material";
import Button from "@mui/material/Button";
import Grid from "@mui/material/Grid";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import React, { useEffect, useState } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import ApplicationStore from "../../../stores/ApplicationStore";
import XenonConnectionStore from "../../../stores/XenonConnectionStore";
import WebGLTest, { WebGLTestState } from "../../../utils/WebGLTest";
import Loader from "../../Loader";

const StyledButton = styled(Button)(({ theme }) => ({
  backgroundColor: theme.palette.YELLOW_BUTTON,
  color: theme.palette.DARK,
  padding: theme.spacing(1, 8),
  fontWeight: 700,
  marginTop: "4px",
  borderRadius: "2px",
  fontSize: theme.typography.pxToRem(14),
  "&:hover": {
    backgroundColor: theme.palette.YELLOW_BUTTON
  }
}));

export default function WebGLTestPage() {
  const [testResults, setTestResults] = useState<WebGLTestState | null>(null);
  const { formatMessage } = useIntl();

  const onPostMessage = () => {
    const { lastMessage } = XenonConnectionStore.getCurrentState();
    // message from WebGLTest class
    const formattedPostMessage = lastMessage as {
      messageName: "glTestResults";
      data: WebGLTestState;
    };
    if (formattedPostMessage.messageName === "glTestResults") {
      const newTestResults = formattedPostMessage.data;
      const params = new URLSearchParams(location.search);
      if (params.get("decomp") === "gl") {
        newTestResults.isTestPassed = false;
      }
      setTestResults(newTestResults);
    }
  };

  useEffect(() => {
    const pageTitle = `${ApplicationStore.getApplicationSetting(
      "defaultTitle"
    )} | ${formatMessage({ id: "webGLTest" })}`;
    document.title = pageTitle;
    XenonConnectionActions.connect();
    XenonConnectionStore.addChangeListener(onPostMessage);
    const testInstance = new WebGLTest();
    testInstance.runTest();
    return () => {
      XenonConnectionStore.removeChangeListener(onPostMessage);
      XenonConnectionActions.disconnect();
    };
  }, []);

  const isTestDataLoaded = Object.keys(testResults || {}).length > 0;
  const isTestPassed = false; // isTestDataLoaded ? testResults?.isTestPassed : false;

  return (
    <Box
      sx={{
        top: `50px`,
        bottom: 0,
        overflow: "hidden",
        position: "absolute",
        width: "100%",
        backgroundColor: theme => theme.palette.SNOKE,
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        flexDirection: "column",
        gap: 2
      }}
    >
      {isTestDataLoaded ? (
        <>
          {isTestPassed ? (
            <Typography
              variant="body1"
              sx={{
                fontSize: theme => theme.typography.pxToRem(18),
                color: theme => theme.palette.LIGHT
              }}
            >
              <FormattedMessage id="deviceIsGoodEnough" />
            </Typography>
          ) : (
            <Box
              sx={{
                fontSize: theme => theme.typography.pxToRem(18),
                color: theme => theme.palette.LIGHT,
                display: "flex",
                flexDirection: "column",
                gap: 1
              }}
            >
              <strong>
                <FormattedMessage id="webglTest.browserDoesntSupportWebGLOrError" />
              </strong>
              <FormattedMessage id="webglTest.possibleSolutions" />
              <ul>
                <li>
                  <FormattedMessage id="webglTest.restartBrowserToReset" />
                </li>
                <li>
                  <FormattedMessage id="webglTest.ensureDriversAreUpToDate" />
                </li>
                <li>
                  <FormattedMessage id="webglTest.enableWebGLInBrowser" />
                </li>
              </ul>
              <FormattedMessage
                tagName="span"
                id="webglTest.forMoreInstructionsReferToHelp"
                values={{
                  // eslint-disable-next-line react/no-unstable-nested-components
                  a: chunks => (
                    <a
                      target="_blank"
                      rel="noopener noreferrer"
                      href="https://kudo.graebert.com/help/index.htm#t=quick_tour%2Ft_before.htm"
                    >
                      {chunks}
                    </a>
                  )
                }}
              />
            </Box>
          )}

          <Typography
            variant="body2"
            sx={{
              fontSize: theme => theme.typography.pxToRem(12),
              color: theme => theme.palette.REY
            }}
          >
            <FormattedMessage
              id="testCompletedIn"
              values={{ time: testResults?.fullTime }}
            />
          </Typography>

          <Grid item>
            <Grid item xs={12}>
              <StyledButton
                data-component="returnButton"
                onClick={() => {
                  window.history.back();
                }}
              >
                <FormattedMessage id="return" />
              </StyledButton>
            </Grid>
          </Grid>
        </>
      ) : (
        <Loader />
      )}
    </Box>
  );
}
