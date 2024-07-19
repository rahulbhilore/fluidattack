import React from "react";
import PropTypes from "prop-types";
import Immutable from "immutable";
import Box from "@material-ui/core/Box";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import Thumbnail from "../../../Thumbnail";

const useStyles = makeStyles(() => ({
  root: {
    display: "flex"
  },
  img: {
    marginRight: "4px"
  },
  text: {
    fontWeight: 900
  }
}));

export default function Filename({ _id, filenameInfo }) {
  const classes = useStyles();
  const filename = filenameInfo.get("filename");
  const thumbnail = filenameInfo.get("thumbnail");

  return (
    <Box
      className={classes.root}
      data-component="version_name"
      data-id={_id}
      data-customname={filename}
    >
      <Thumbnail
        src={thumbnail}
        fileId={_id}
        width={20}
        height={20}
        className={classes.img}
      />
      <Typography className={classes.text} variant="body2">
        {filename}
      </Typography>
    </Box>
  );
}

Filename.propTypes = {
  _id: PropTypes.string.isRequired,
  filenameInfo: PropTypes.instanceOf(Immutable.Map).isRequired
};
