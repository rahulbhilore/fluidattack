import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Loader from "../../Loader";
import Thumbnail from "../../Thumbnail";
import FilesListActions from "../../../actions/FilesListActions";
import DialogBody from "../DialogBody";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(() => ({
  message: {
    textAlign: "center"
  },
  initialThumbnail: {
    textAlign: "center"
  },
  thumbnail: {
    maxWidth: "85%",
    maxHeight: "500px"
  }
}));

// TODO: open dialog in full screen mode ?
export default function COMPARE_DRAWINGS({ info }) {
  const { file, fileToCompare } = info;
  const [comparisonURL, setURL] = useState("");
  useEffect(() => {
    FilesListActions.compareDrawings(file, fileToCompare)
      .then(response => {
        setURL(response.url);
      })
      .catch(err => {
        SnackbarUtils.alertError(err.message);
      });
  }, [file, fileToCompare]);
  const classes = useStyles();
  return (
    <DialogBody>
      <Grid container spacing={2}>
        <Grid item xs={12} md={6} className={classes.initialThumbnail}>
          <Thumbnail src={file.thumbnail} />
        </Grid>
        <Grid item xs={12} md={6} className={classes.initialThumbnail}>
          <Thumbnail src={fileToCompare.thumbnail} />
        </Grid>
        <Grid item xs={12} className={classes.initialThumbnail}>
          <Thumbnail
            className={classes.thumbnail}
            src={comparisonURL}
            fallback={
              <Loader
                isModal
                message={
                  <Typography variant="body1" className={classes.message}>
                    <FormattedMessage id="comparingDrawings" />
                  </Typography>
                }
              />
            }
          />
        </Grid>
      </Grid>
    </DialogBody>
  );
}

COMPARE_DRAWINGS.propTypes = {
  info: PropTypes.shape({
    file: PropTypes.shape({
      id: PropTypes.string.isRequired,
      versionId: PropTypes.string,
      thumbnail: PropTypes.string.isRequired
    }).isRequired,
    fileToCompare: PropTypes.shape({
      id: PropTypes.string.isRequired,
      versionId: PropTypes.string,
      thumbnail: PropTypes.string.isRequired
    }).isRequired
  }).isRequired
};
