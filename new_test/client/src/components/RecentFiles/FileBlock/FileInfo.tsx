import React from "react";
import PropTypes from "prop-types";
import { Box, styled } from "@mui/material";
import StorageIcons from "../../../constants/appConstants/StorageIcons";
import ApplicationStore from "../../../stores/ApplicationStore";
import DSOriginal from "../../../assets/images/DS/ds_original.png";
import RelativeTime from "../../RelativeTime/RelativeTime";

const StorageImg = styled("img")({
  maxWidth: "20px",
  maxHeight: "20px"
});

const TimeSpan = styled("span")(({ theme }) => ({
  float: "right",
  color: theme.palette.CLONE,
  fontSize: "12px",
  verticalAlign: "top",
  lineHeight: "20px"
}));

export default function FileInfo({
  storageName,
  date,
  dataComponent,
  name
}: {
  storageName: string;
  date: number;
  dataComponent: string;
  name: string;
}) {
  let storageIconURL =
    StorageIcons[`${storageName}ActiveSVG` as keyof typeof StorageIcons];
  if (storageName === "fluorine" || storageName === "internal") {
    const product = ApplicationStore.getApplicationSetting("product");
    if (product === "DraftSight") {
      storageIconURL = DSOriginal;
    }
  }

  return (
    <Box data-component={dataComponent} data-name={name}>
      <StorageImg src={storageIconURL} alt={storageName} />
      <TimeSpan>
        <RelativeTime timestamp={date} />
      </TimeSpan>
    </Box>
  );
}

FileInfo.propTypes = {
  storageName: PropTypes.string.isRequired,
  date: PropTypes.number.isRequired,
  dataComponent: PropTypes.string,
  name: PropTypes.string
};

FileInfo.defaultProps = {
  dataComponent: "fileInfo",
  name: "unknown"
};
