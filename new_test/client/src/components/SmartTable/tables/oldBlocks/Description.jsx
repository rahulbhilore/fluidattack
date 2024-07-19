import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import { Box } from "@material-ui/core";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import processStore from "../../../../stores/ProcessStore";
import * as ProcessConstants from "../../../../constants/ProcessContants";

const useStyles = makeStyles(theme => ({
  root: {
    display: "flex"
  },
  name: {
    fontSize: theme.typography.pxToRem(12),
    color: theme.palette.SNOKE
  },
  process: {
    color: theme.palette.OBI,
    fontSize: theme.typography.pxToRem(12),
    fontWeight: 700,
    textTransform: "uppercase"
  }
}));

export default function Description({ _id, description, data }) {
  const classes = useStyles();

  const { processId } = data;
  const [process, setProcess] = useState(false);
  const [id, setId] = useState(_id);

  const handleProcess = processInfo => {
    switch (processInfo.status) {
      case ProcessConstants.START:
      case ProcessConstants.STEP:
      case ProcessConstants.MODIFY:
        setProcess(processInfo);
        break;
      case ProcessConstants.END:
        setProcess(false);
        break;
      default:
        break;
    }
  };
  useEffect(() => {
    processStore.addChangeListener(processId || id, handleProcess);
    return () => {
      processStore.removeChangeListener(processId || id, handleProcess);
    };
  }, [id, data]);

  useEffect(() => {
    setProcess(false);
    setId(_id);
  }, [_id]);

  if (process && Object.keys(process).length > 0) {
    if (process.value || process.type) {
      return (
        <div>
          <span className={classes.process}>
            {process.value && !_.isNaN(parseFloat(process.value)) ? (
              `${
                parseFloat(process.value) > 99.99
                  ? 99.99
                  : parseFloat(process.value)
              }%`
            ) : (
              <FormattedMessage id={process.type} />
            )}
          </span>
        </div>
      );
    }
  }

  return (
    <Box className={classes.root}>
      <Box>
        <Typography
          className={classes.name}
          variant="body2"
          data-component="blockDescription"
          data-text={description}
        >
          {description}
        </Typography>
      </Box>
    </Box>
  );
}

Description.propTypes = {
  description: PropTypes.string,
  _id: PropTypes.string.isRequired,
  data: PropTypes.shape({
    processId: PropTypes.string
  }).isRequired
};

Description.defaultProps = {
  description: ""
};
