import React, { useState, useEffect } from "react";
import { Box } from "@mui/material";
import { makeStyles } from "@material-ui/core/styles";
import ClientFile from "../../../stores/objects/types/ClientFile";
import FilesListActions from "../../../actions/FilesListActions";
import LinkDisplay from "./LinkDisplay";
import LinkNotFound from "./LinkNotFound";

const useStyles = makeStyles(theme => ({
  root: {
    // @ts-ignore
    backgroundColor: theme.palette.SNOKE,
    height: "100vh",
    position: "relative",
    flexGrow: 1,
    display: "flex",
    alignItems: "center",
    justifyContent: "center"
  }
}));

type Props = {
  params: { fileId: string; versionId: string };
  location: { query: { token: string } };
};

export default function DownloadLinkPage({ params, location }: Props) {
  const classes = useStyles();

  const [fileInfo, setFileInfo] = useState<ClientFile | undefined>(undefined);
  const [isLoadingInfo, setLoadingInfo] = useState(false);
  const [loadInfoError, setLoadInfoError] = useState<string | undefined>(
    undefined
  );

  useEffect(() => {
    if (!isLoadingInfo) {
      setLoadingInfo(true);

      FilesListActions.getLinkObjectInfo(
        params.fileId,
        params.versionId,
        location.query.token,
        undefined
      )
        .then((data: ClientFile) => {
          setLoadingInfo(false);
          setFileInfo(data);
        })
        .catch(err => {
          setLoadingInfo(false);
          setLoadInfoError(err.text || err.message || err);
        });
    }
  }, []);

  if (loadInfoError) {
    return (
      <Box className={classes.root}>
        <LinkNotFound />
      </Box>
    );
  }

  return (
    <Box className={classes.root}>
      <LinkDisplay
        fileId={params.fileId}
        versionId={params.versionId}
        token={location.query.token}
        fileInfo={fileInfo}
        isLoadingInfo={isLoadingInfo}
      />
    </Box>
  );
}
