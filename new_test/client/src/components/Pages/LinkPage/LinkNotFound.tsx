import { makeStyles } from "@material-ui/core/styles";
import { Box, Typography } from "@mui/material";
import React from "react";
import { useIntl } from "react-intl";
import kudoSvg from "../../../assets/images/Commander/KudoBrandings.svg";

const useStyles = makeStyles(theme => ({
  mainContainer: {
    backgroundColor: "#2D3035",
    borderRadius: "6px",
    width: "90%",
    maxWidth: "1000px",
    height: "450px",
    padding: "40px",
    gap: "60px",
    display: "flex",
    flexDirection: "column",
    alignItems: "center",
    justifyContent: "center",
    [theme.breakpoints.down("xs")]: {
      padding: "15px 15px",
      gap: "30px",
      justifyContent: "space-around",
      height: "auto"
    }
  },

  infoContainer: {
    backgroundColor: "#3C4553",
    borderRadius: "6px",
    padding: "30px",
    width: "100%",
    display: "flex",
    flexDirection: "column",
    justifyContent: "space-between",
    alignItems: "center",
    [theme.breakpoints.down("xs")]: {
      gap: "20px"
    }
  },

  imageContainer: {
    objectFit: "contain",
    height: "70px",
    width: "100%",
    display: "flex",
    justifyContent: "center"
  },

  commanderBrandSvg: {
    maxWidth: "100%",
    maxHeight: "100%",
    display: "block",
    justifyContent: "center"
  }
}));

export default function LinkNotFound() {
  const translations = useIntl();
  const classes = useStyles();

  return (
    <Box className={classes.mainContainer}>
      <Box className={classes.imageContainer}>
        <img
          className={classes.commanderBrandSvg}
          src={kudoSvg}
          alt="ARES Commander"
        />
      </Box>
      <Box className={classes.infoContainer}>
        <Typography
          sx={{
            fontSize: "20px",
            fontStyle: "normal",
            lineHeight: "36px",
            fontWeight: 500,
            textAlign: "center"
          }}
        >
          {translations.formatMessage({ id: "linkNotFound" })}
        </Typography>
        <Typography
          sx={{
            fontSize: "16px",
            fontStyle: "normal",
            lineHeight: "36px",
            fontWeight: 400
          }}
        >
          {translations.formatMessage({ id: "contactLinkOwner" })}
        </Typography>
      </Box>
    </Box>
  );
}
