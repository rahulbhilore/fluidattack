import { Grid, SxProps, Typography } from "@mui/material";
import { makeStyles } from "@mui/styles";
import clsx from "clsx";
import React, { useMemo } from "react";
import { FormattedMessage } from "react-intl";
import arrowDownSVG from "../../../assets/images/Chevron-Down.svg";
import userIconSVG from "../../../assets/images/userMenu/user-icon.svg";
import ApplicationStore from "../../../stores/ApplicationStore";

const UserText = ({
  userName,
  licenseType
}: {
  userName: string;
  licenseType: string;
}) => {
  const textSx = useMemo<SxProps>(
    () => ({
      textAlign: "left",
      margin: "auto",
      whiteSpace: "nowrap"
    }),
    []
  );

  return (
    <Grid
      item
      sx={{
        flexDirection: "column",
        justifyContent: "center",
        width: "calc(100% - 40px)",
        overflow: "hidden"
      }}
    >
      <Typography
        sx={{
          color: theme => theme.palette.LIGHT,
          fontSize: 12,
          ...textSx
        }}
      >
        {userName}
      </Typography>
      {licenseType.length > 0 && (
        <Typography
          sx={{
            ...textSx,
            fontSize: 10,
            color: theme => theme.palette.REY
          }}
        >
          <FormattedMessage id={licenseType.toLowerCase()} />
        </Typography>
      )}
    </Grid>
  );
};

const useStyles = makeStyles(() => ({
  img: {
    marginTop: "6px",
    width: "20px",
    transition: "transform .3s ease-in-out"
  },
  imgRotated: {
    transform: "rotate(180deg)"
  }
}));

type PropType = {
  open: boolean;
  isMobile: boolean;
  forceHide: boolean;
  userInfo: {
    licenseType: string;
    name: string;
    surname: string;
    expirationDate: number;
    username: string;
    email: string;
  };
};

export default function UserName({
  userInfo,
  open,
  isMobile,
  forceHide
}: PropType) {
  const classes = useStyles();

  let licenseType = userInfo.licenseType || "";

  if (
    ApplicationStore.getApplicationSetting("featuresEnabled")
      .independentLogin === true ||
    (licenseType.toLowerCase().trim() !== "perpetual" &&
      (userInfo.expirationDate <= Date.now() ||
        licenseType.toLowerCase().trim().length === 0))
  ) {
    licenseType = "";
  }

  const userName = useMemo(() => {
    if (`${userInfo.name ?? ""}${userInfo.surname ?? ""}`.length > 0) {
      return `${userInfo.name} ${userInfo.surname ?? ""}`.trim();
    }
    return ((userInfo.username || userInfo.email) ?? " ").trim();
  }, [userInfo]);

  const showUserName = useMemo(() => {
    if (isMobile) {
      if (forceHide) return false;

      return open;
    }
    return true;
  }, [isMobile, open, forceHide]);
  if (!userInfo) return null;
  if (userName.length === 0) return null;

  return (
    <Grid container>
      {showUserName && (
        <UserText licenseType={licenseType} userName={userName} />
      )}
      <Grid item>
        <img src={userIconSVG} className={classes.img} alt="userName" />
        <img
          src={arrowDownSVG}
          className={clsx({
            [classes.img]: true,
            [classes.imgRotated]: open
          })}
          alt="arrow"
        />
      </Grid>
    </Grid>
  );
}
