import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import { Box } from "@material-ui/core";

const useStyles = makeStyles(theme => ({
  root: {
    display: "flex"
  },
  name: {
    fontSize: theme.typography.pxToRem(14),
    color: theme.palette.SNOKE
  }
}));

export default function Tags({ tags }) {
  const classes = useStyles();
  return (
    <Box className={classes.root}>
      <Box>
        <Typography
          className={classes.name}
          variant="body2"
          data-component="blockTags"
          data-text={tags}
        >
          {tags}
        </Typography>
      </Box>
    </Box>
  );
}

Tags.propTypes = {
  tags: PropTypes.string
};

Tags.defaultProps = {
  tags: ""
};
