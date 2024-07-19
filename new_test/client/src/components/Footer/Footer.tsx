import React from "react";
import { FormattedMessage } from "react-intl";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useTheme } from "@mui/material";
import ApplicationStore from "../../stores/ApplicationStore";
import SidebarFooter from "./SidebarFooter";
import Revision from "./Revision";
import TermsOfUse from "./links/TermsOfUse";
import Copyright from "./Copyright";
import BasicLink from "./links/BasicLink";

type Props = {
  isIndexPage?: boolean;
  isSideBar?: boolean;
  customMargins?: number[];
  customPaddings?: number[];
  isFixed?: boolean;
};

export default function Footer({
  isIndexPage,
  isSideBar,
  customMargins,
  customPaddings,
  isFixed
}: Props) {
  const { independentLogin: isIndependentLogin } =
    ApplicationStore.getApplicationSetting("featuresEnabled");
  const customization = ApplicationStore.getApplicationSetting("customization");
  const vendor = ApplicationStore.getApplicationSetting("vendor");
  const product = ApplicationStore.getApplicationSetting("product");

  const {
    termsOfUse,
    privacyPolicyLink,
    showEULAInsteadOfPP,
    EULALink,
    showEULA,
    showLinksInIndexFooter
  } = customization;

  const theme = useTheme();

  if (isIndexPage === true) {
    return (
      <Box
        component="footer"
        sx={{
          width: "100%",
          marginTop: theme.spacing(2),
          paddingBottom: theme.spacing(2),
          backgroundColor: "transparent",
          [theme.breakpoints.down("md")]: {
            paddingBottom: 0
          },
          [theme.breakpoints.up("lg")]: {
            position: "fixed",
            bottom: 0
          }
        }}
      >
        <Copyright isSmall vendor={vendor} />
        {isIndependentLogin && !showLinksInIndexFooter ? (
          <Typography
            variant="body2"
            sx={{
              color: theme.palette.CLONE,
              fontSize: theme.typography.pxToRem(11),
              textAlign: "center"
            }}
          >
            <FormattedMessage
              id="agreementFooter"
              values={{
                product,
                vendor
              }}
            />
          </Typography>
        ) : (
          <Box
            sx={{
              display: "flex",
              flexDirection: "row",
              justifyContent: "center",
              alignItems: "center",
              gap: "30px"
            }}
          >
            <TermsOfUse link={termsOfUse} />
            {showEULAInsteadOfPP ? (
              <BasicLink href={EULALink} messageId="EULA" />
            ) : (
              <BasicLink
                dataComponent="privacyPolicyLink"
                href={privacyPolicyLink}
                messageId="privacyPolicy"
              />
            )}
          </Box>
        )}
        <Revision
          sx={{
            fontWeight: "bold"
          }}
        />
      </Box>
    );
  }
  if (isSideBar === true) {
    return <SidebarFooter vendor={vendor} customization={customization} />;
  }
  return (
    <Box
      component="footer"
      sx={{
        borderTop: "solid 1px #ddd",
        margin: "0 30px",
        height: "45px",
        "@media (max-width: 990px)": {
          margin: 0,
          padding: "5px",
          textAlign: "center"
        },
        ...(isFixed ? { position: "fixed", bottom: 0, width: "100%" } : {}),
        ...(customMargins ? { margin: `${customMargins.join("px ")}px` } : {}),
        ...(customPaddings
          ? { padding: `${customPaddings.join("px ")}px` }
          : {})
      }}
    >
      <Copyright
        vendor={vendor}
        isSmall={false}
        sx={{
          display: "inline-block",
          lineHeight: "45px",
          color: theme.palette.CLONE,
          fontWeight: "normal",
          "@media (max-width: 990px)": {
            lineHeight: "normal"
          }
        }}
      />
      <Box
        sx={{
          float: "right",
          marginRight: "135px",
          "@media (max-width: 990px)": {
            float: "none",
            marginRight: 0
          }
        }}
      >
        <Revision
          sx={{
            display: "inline-block",
            lineHeight: "45px",
            width: "auto",
            marginTop: 0,
            marginRight: theme.spacing(3),
            "@media (max-width: 990px)": {
              lineHeight: "normal"
            }
          }}
        />
        <TermsOfUse
          link={termsOfUse}
          sx={{
            display: "inline-block",
            lineHeight: "45px",
            marginRight: theme.spacing(3),
            "&,&:hover,&:focus,&:active": {
              textDecoration: "none",
              color: theme.palette.OBI
            },
            "@media (max-width: 990px)": {
              lineHeight: "normal"
            }
          }}
        />
        {showEULAInsteadOfPP ? (
          <BasicLink
            href={EULALink}
            messageId="EULA"
            sx={{
              display: "inline-block",
              lineHeight: "45px",
              marginRight: theme.spacing(3),
              "&,&:hover,&:focus,&:active": {
                textDecoration: "none",
                color: theme.palette.OBI
              },
              "@media (max-width: 990px)": {
                lineHeight: "normal"
              }
            }}
          />
        ) : (
          <BasicLink
            href={privacyPolicyLink}
            messageId="EULA"
            sx={{
              display: "inline-block",
              lineHeight: "45px",
              marginRight: theme.spacing(3),
              "&,&:hover,&:focus,&:active": {
                textDecoration: "none",
                color: theme.palette.OBI
              },
              "@media (max-width: 990px)": {
                lineHeight: "normal"
              }
            }}
          />
        )}
        {showEULA ? (
          <BasicLink
            href={EULALink}
            messageId="EULA"
            sx={{
              display: "inline-block",
              lineHeight: "45px",
              marginRight: theme.spacing(3),
              "&,&:hover,&:focus,&:active": {
                textDecoration: "none",
                color: theme.palette.OBI
              },
              "@media (max-width: 990px)": {
                lineHeight: "normal"
              }
            }}
          />
        ) : null}
      </Box>
    </Box>
  );
}
