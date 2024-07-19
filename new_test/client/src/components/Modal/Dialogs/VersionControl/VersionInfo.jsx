import React from "react";
import PropTypes from "prop-types";
import { FormattedMessage, FormattedDate, FormattedTime } from "react-intl";
import Immutable from "immutable";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Grid } from "@material-ui/core";
import Typography from "@material-ui/core/Typography";
import MainFunctions from "../../../../libraries/MainFunctions";
import Thumbnail from "../../../Thumbnail";

const useStyles = makeStyles(theme => ({
  bold: {
    fontWeight: 900,
    fontSize: theme.typography.pxToRem(13)
  },
  thumbnail: {
    margin: "0 auto"
  }
}));

export default function VersionInfo({ selectedVersions, versions, fileId }) {
  const classes = useStyles();

  if (!versions.size) return null;

  const version = versions.find(
    elem => elem.get("_id") === selectedVersions[0]
  );

  return selectedVersions.length === 1 && version ? (
    <Grid container spacing={1}>
      <Thumbnail
        key={version.get("thumbnail")}
        fileId={fileId}
        src={version.get("thumbnail")}
        width={240}
        height={160}
        className={classes.thumbnail}
      />
      <Grid item xs={6}>
        <Typography className={classes.bold} variant="body2">
          <FormattedMessage id="creationTime" />:
        </Typography>
      </Grid>
      <Grid item xs={5}>
        <Typography variant="body2">
          <FormattedDate value={version.get("creationTime")} />
        </Typography>
        <Typography variant="body2">
          <FormattedTime value={version.get("creationTime")} />
        </Typography>
      </Grid>
      <Grid item xs={6}>
        <Typography className={classes.bold} variant="body2">
          <FormattedMessage id="size" />:
        </Typography>
      </Grid>
      <Grid item xs={5}>
        <Typography variant="body2">
          {MainFunctions.formatBytes(version.get("size"))}
        </Typography>
      </Grid>
    </Grid>
  ) : (
    <Grid container spacing={1}>
      <Grid item xs={5}>
        <Typography className={classes.bold} variant="body2">
          <FormattedMessage id="selectedVersions" />:
        </Typography>
      </Grid>
      <Grid item xs={5}>
        <Typography variant="body2">{selectedVersions.length}</Typography>
      </Grid>
    </Grid>
  );
}

VersionInfo.propTypes = {
  fileId: PropTypes.string.isRequired,
  selectedVersions: PropTypes.arrayOf(PropTypes.string).isRequired,
  versions: PropTypes.instanceOf(Immutable.List).isRequired
};
