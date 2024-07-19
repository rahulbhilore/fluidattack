import { makeStyles, useTheme } from "@material-ui/core/styles";
import { Box, useMediaQuery } from "@mui/material";
import React, { useCallback, useState } from "react";
import { useIntl } from "react-intl";
import kudoSvg from "../../../assets/images/Commander/KudoBrandings.svg";
import LinkThumbnail from "./LinkThumbnail";
import LinkObjectInfo from "./LinkObjectInfo";
import LinkButton from "./LinkButton";
import FilesListActions from "../../../actions/FilesListActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import ClientFile from "../../../stores/objects/types/ClientFile";
import MainFunctions from "../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  mainContainer: {
    backgroundColor: "#2D3035",
    borderRadius: "6px",
    width: "90%",
    maxWidth: "1000px",
    height: "435px",
    padding: "40px",
    gap: "40px",
    display: "flex",
    [theme.breakpoints.down("xs")]: {
      padding: "15px",
      gap: "15px",
      height: "auto"
    }
  },

  infoContainer: {
    flex: "1 1 0",
    width: 0,
    display: "flex",
    flexDirection: "column",
    justifyContent: "space-between",
    [theme.breakpoints.down("xs")]: {
      gap: "15px"
    }
  },

  commanderBrandSvg: {
    height: "100%",
    maxWidth: "100%",
    maxHeight: "100%",
    display: "block",
    justifyContent: "center"
  },

  buttonsBox: {
    width: "100%",
    display: "flex",
    gap: "30px",
    height: "65px",
    [theme.breakpoints.down("xs")]: {
      gap: "15px"
    }
  },

  thumbnailContainer: {
    flex: "1 1 0",
    width: 0,
    height: "100%",
    overflow: "hidden"
  },

  mobileThumbnailBox: {
    width: "100%",
    height: "35vh",
    maxHeight: "300px",
    display: "flex",
    alignItems: "center",
    justifyContent: "center"
  }
}));

type Props = {
  fileId: string;
  versionId: string;
  token: string;
  fileInfo: ClientFile | undefined;
  isLoadingInfo: boolean;
  password?: string;
};

export default function LinkDisplay({
  fileId,
  versionId,
  token,
  fileInfo,
  isLoadingInfo,
  password
}: Props) {
  const classes = useStyles();
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("xs"));
  const translations = useIntl();

  const [downloadType, setDownloadType] = useState<
    "none" | "default" | "asPdf"
  >("none");

  const handleDownload = useCallback(
    (type: "default" | "asPdf") => {
      if (fileInfo && downloadType === "none") {
        setDownloadType(type);

        FilesListActions.downloadLinkObject(
          fileId,
          versionId,
          token,
          type === "asPdf",
          password
        )
          .then((data: ArrayBuffer) => {
            const name =
              type === "asPdf"
                ? `${MainFunctions.parseObjectName(fileInfo.name).name}.pdf`
                : fileInfo.name;
            MainFunctions.finalizeDownload(data, name);
            setDownloadType("none");
          })
          .catch(err => {
            setDownloadType("none");
            SnackbarUtils.alertError(err.text || err.message || err);
          });
      }
    },
    [fileId, versionId, token, password, fileInfo, downloadType]
  );

  const handleDefaultDownload = useCallback(() => {
    handleDownload("default");
  }, [handleDownload]);

  const handlePdfDownload = useCallback(() => {
    handleDownload("asPdf");
  }, [handleDownload]);

  return (
    <Box className={classes.mainContainer}>
      <Box className={classes.infoContainer}>
        <Box sx={{ objectFit: "contain" }} height="65px" width="100%">
          <img
            className={classes.commanderBrandSvg}
            src={kudoSvg}
            alt="ARES Commander"
          />
        </Box>

        {isMobile && (
          <Box className={classes.mobileThumbnailBox}>
            <LinkThumbnail
              isLoading={isLoadingInfo || !fileInfo}
              thumbnail={fileInfo?.thumbnail || ""}
              preview={fileInfo?.preview || ""}
              aspect="vertical"
            />
          </Box>
        )}

        <LinkObjectInfo
          showSkeleton={isLoadingInfo || !fileInfo}
          info={
            !isLoadingInfo && fileInfo
              ? {
                  objectName: fileInfo.name,
                  versionId,
                  size: fileInfo.size,
                  updateDate: fileInfo.updateDate
                }
              : undefined
          }
        />
        <Box className={classes.buttonsBox}>
          <LinkButton
            showSkeleton={isLoadingInfo}
            disabled={isLoadingInfo || downloadType === "asPdf"}
            onClick={handleDefaultDownload}
            isLoading={downloadType === "default"}
            variant="primary"
            text={
              downloadType === "default"
                ? translations.formatMessage({ id: "downloading" })
                : translations.formatMessage({ id: "downloadDWG" })
            }
          />
          <LinkButton
            showSkeleton={isLoadingInfo}
            disabled={isLoadingInfo || downloadType === "default"}
            onClick={handlePdfDownload}
            isLoading={downloadType === "asPdf"}
            variant="secondary"
            text={
              downloadType === "asPdf"
                ? translations.formatMessage({ id: "converting" })
                : translations.formatMessage({ id: "downloadPDF" })
            }
          />
        </Box>
      </Box>
      {!isMobile && (
        <Box
          sx={{ backgroundColor: isLoadingInfo ? "transparent" : undefined }}
          className={classes.thumbnailContainer}
        >
          <LinkThumbnail
            isLoading={isLoadingInfo || !fileInfo}
            thumbnail={fileInfo?.thumbnail || ""}
            preview={fileInfo?.preview || ""}
            aspect="vertical"
          />
        </Box>
      )}
    </Box>
  );
}
