import { Box, Chip, Typography } from "@mui/material";
import React from "react";
import { makeStyles, useTheme } from "@material-ui/core/styles";
import LinkSkeleton from "./LinkSkeleton";
import MainFunctions from "../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  mainBox: {
    display: "flex",
    gap: "24px",
    flexDirection: "column",
    justifyContent: "space-between",
    [theme.breakpoints.down("xs")]: {
      gap: "15px"
    }
  },
  nameLabelBox: {
    width: "100%",
    backgroundColor: "#3C4553",
    borderRadius: "3px",
    gap: "15px",
    display: "flex",
    justifyContent: "center",
    height: "90px",
    alignItems: "center",
    overflow: "hidden"
  },
  nameBox: {
    display: "flex",
    marginTop: "auto",
    marginBottom: "auto",
    height: "max-content",
    maxHeight: "60px",
    overflowX: "hidden",
    overflowY: "auto",
    "&::-webkit-scrollbar": {
      width: "3px"
    }
  },
  additionalInfoBox: {
    width: "100%",
    display: "flex",
    gap: "24px",
    alignItems: "center",
    justifyContent: "center",
    [theme.breakpoints.down("xs")]: {
      gap: "15px"
    }
  }
}));

const getLabelProps = (
  versionId: "latest" | "last_printed" | string
): {
  label: "Latest" | "Last printed" | "Specific";
  color: React.CSSProperties["color"];
} => {
  if (versionId === "latest") {
    return {
      label: "Latest",
      color: "#E3E4E7"
    };
  }

  if (versionId === "last_printed") {
    return {
      label: "Last printed",
      color: "#CB5B0A"
    };
  }

  return {
    label: "Specific",
    color: "#138DD1"
  };
};

type Props = {
  showSkeleton: boolean;
  info?: {
    objectName: string;
    versionId: string;
    size: string;
    updateDate: number;
  };
};

export default function LinkObjectInfo({ showSkeleton, info }: Props) {
  const classes = useStyles();
  const theme = useTheme();

  if (showSkeleton || !info) {
    return (
      <Box className={classes.mainBox}>
        <LinkSkeleton height="80px" width="100%" />
        <LinkSkeleton height="20px" width="100%" />
        <LinkSkeleton height="20px" width="100%" />
      </Box>
    );
  }

  const { objectName, versionId, size, updateDate } = info;
  const { name, extension } = MainFunctions.parseObjectName(objectName);
  const { label, color } = getLabelProps(versionId);

  return (
    <Box className={classes.mainBox}>
      <Box
        className={classes.nameLabelBox}
        sx={{
          padding: name.length > 27 ? "15px" : "30px",
          [theme.breakpoints.down("xs")]: {
            padding: "15px"
          }
        }}
      >
        <Box className={classes.nameBox}>
          <Typography
            sx={{
              fontSize: "22px",
              fontStyle: "normal",
              fontWeight: 500,
              lineHeight: "normal",
              [theme.breakpoints.down("xs")]: {
                fontSize: "19px"
              }
            }}
          >
            {name}
          </Typography>
        </Box>
        <Chip
          sx={{
            padding: "6px 0",
            borderRadius: "3px",
            backgroundColor: "#1E2023",
            color: "#ffffff",
            height: "100%",
            width: "auto",

            alignSelf: "center",
            fontSize: "16px",
            fontStyle: "normal",
            fontWeight: 500,
            lineHeight: "normal",
            [theme.breakpoints.down("xs")]: {
              fontSize: "15px"
            }
          }}
          label={extension.toUpperCase()}
        />
      </Box>
      <Box className={classes.additionalInfoBox}>
        <Chip
          sx={{
            padding: "7px 10px",
            minWidth: "100px",
            maxHeight: "30px",
            borderRadius: "3px",
            backgroundColor: color,
            color: "#000000",
            height: "auto",

            fontSize: "12px",
            fontStyle: "normal",
            fontWeight: 400,
            lineHeight: "normal",

            [theme.breakpoints.down("xs")]: {
              padding: "5px 0",
              minWidth: "70px"
            }
          }}
          label={label}
        />
        <Typography
          sx={{
            fontSize: "14px",
            fontStyle: "normal",
            fontWeight: 400,
            lineHeight: "28px",

            [theme.breakpoints.down("xs")]: {
              fontSize: "12px",
              fontWeight: 300
            }
          }}
        >
          Size: {size}
        </Typography>
        <Typography
          sx={{
            fontSize: "14px",
            fontStyle: "normal",
            fontWeight: 400,
            lineHeight: "28px",

            [theme.breakpoints.down("xs")]: {
              fontSize: "12px",
              fontWeight: 300
            }
          }}
        >
          Modified:
          {` ${new Date(updateDate).toLocaleDateString()}`}
        </Typography>
      </Box>
    </Box>
  );
}
