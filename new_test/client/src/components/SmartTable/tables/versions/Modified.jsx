import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import { FormattedDate, FormattedMessage, FormattedTime } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import ProcessStore from "../../../../stores/ProcessStore";
import * as ProcessConstants from "../../../../constants/ProcessContants";

const useStyles = makeStyles(theme => ({
  root: {
    fontSize: "12px"
  },
  processName: {
    fontSize: "12px",
    color: `${theme.palette.OBI}!important`,
    textTransform: "uppercase"
  }
}));

export default function Modified({ creationTime, _id, getter }) {
  const classes = useStyles();
  const [process, setProcess] = useState(false);
  const isService = getter("isService");

  const handleStart = () => {
    setProcess(true);
  };

  const handleEnd = () => {
    setProcess(false);
  };

  useEffect(() => {
    ProcessStore.addChangeListener(ProcessConstants.START + _id, handleStart);
    ProcessStore.addChangeListener(ProcessConstants.END + _id, handleEnd);

    return () => {
      ProcessStore.removeChangeListener(
        ProcessConstants.START + _id,
        handleStart
      );
      ProcessStore.removeChangeListener(ProcessConstants.END + _id, handleEnd);
    };
  }, []);

  if (!process && !isService)
    return (
      <Typography className={classes.root} variant="body2">
        <FormattedDate value={creationTime} /> -&nbsp;
        <FormattedTime value={creationTime} />
      </Typography>
    );

  return (
    <Typography className={classes.processName} variant="body2">
      <FormattedMessage id={isService ? "uploading" : "downloading"} />
    </Typography>
  );
}

Modified.propTypes = {
  creationTime: PropTypes.number.isRequired,
  _id: PropTypes.string.isRequired,
  getter: PropTypes.func.isRequired
};
