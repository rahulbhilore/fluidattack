import { Grid, Link, Stack, useMediaQuery, useTheme } from "@mui/material";
import React from "react";
import { FormattedMessage, useIntl } from "react-intl";
import customerPortalPNG from "../../../../assets/images/customer-portal.png";
import ApplicationStore from "../../../../stores/ApplicationStore";
import UserInfoStore from "../../../../stores/UserInfoStore";
import AccountInfoItem from "./AccountInfoItem";
import AccountInformations from "./AccountInformations";

export default function AccountDataPageContent() {
  const companyInfo = UserInfoStore.getUserInfo("company");
  const { companiesAdmin, companiesAll } =
    ApplicationStore.getApplicationSetting("featuresEnabled");
  const { formatMessage } = useIntl();
  const customerPortalUrl =
    ApplicationStore.getApplicationSetting("customerPortalURL");
  const theme = useTheme();
  const isSmallScreen = useMediaQuery(theme.breakpoints.down("sm"));

  return (
    <Stack>
      <Grid container rowGap={4}>
        <Grid
          xs={12}
          item
          display="flex"
          justifyContent="space-between"
          alignItems="center"
        >
          <img
            src={customerPortalPNG}
            height={isSmallScreen ? 50 : 78}
            alt={formatMessage({ id: "graebertCustomerPortal" })}
          />
          <Link
            data-component="edit-profile-link"
            href={`${customerPortalUrl}/profile/index/contact`}
            rel="noopener noreferrer"
            sx={{
              backgroundColor:
                theme.palette.button.primary.contained.background.standard,
              borderRadius: 1,
              color: theme.palette.button.primary.contained.textColor,
              fontFamily: '"Roboto","Helvetica","Arial",sans-serif',
              fontSize: theme.spacing(1.75),
              fontWeight: 500,
              p: theme.spacing(1.25, 2),
              textDecoration: "none"
            }}
            target="_blank"
          >
            <FormattedMessage id="editMyProfile" />
          </Link>
        </Grid>
        <Grid xs={12} item>
          <AccountInformations>
            <AccountInfoItem id="firstName" userStoreKey="name" />
            <AccountInfoItem id="surname" userStoreKey="surname" />
            <AccountInfoItem id="email" userStoreKey="email" />
            {(companiesAll ||
              (companiesAdmin && UserInfoStore.getUserInfo("isAdmin"))) &&
              companyInfo.name.length > 0 && (
                <AccountInfoItem id="company" renderValue={companyInfo.name} />
              )}
          </AccountInformations>
        </Grid>
      </Grid>
    </Stack>
  );
}
