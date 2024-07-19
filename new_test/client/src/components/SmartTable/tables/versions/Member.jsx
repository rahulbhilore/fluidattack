import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import LinearProgress from "@material-ui/core/LinearProgress";
import ProcessStore from "../../../../stores/ProcessStore";
import * as ProcessConstants from "../../../../constants/ProcessContants";

const useStyles = makeStyles(theme => ({
  root: {
    fontSize: "12px"
  },
  percentageLabel: {
    color: theme.palette.JANGO,
    fontSize: theme.typography.pxToRem(11),
    paddingRight: theme.spacing(1),
    display: "inline-block"
  },
  progress: {
    height: "8px",
    backgroundColor: theme.palette.REY,
    display: "inline-block",
    width: "80%",
    verticalAlign: "middle",
    margin: "30px 0",
    "&.templates": {
      margin: 0
    }
  },
  bar: {
    backgroundColor: theme.palette.OBI
  }
}));

export default function Member({ member, _id, getter }) {
  const classes = useStyles();
  const isService = getter("isService");
  const [process, setProcess] = useState(false);
  const [value, setValue] = useState(0);
  const handleProcess = processData => {
    const { status, value: processValue } = processData;
    switch (status) {
      case ProcessConstants.START: {
        setProcess(true);
        break;
      }
      case ProcessConstants.END: {
        setProcess(false);
        setValue(0);
        break;
      }
      case ProcessConstants.STEP: {
        if (processValue) setValue(processValue);
        if (!process && isService) setProcess(true);
        break;
      }
      default:
        break;
    }
  };

  useEffect(() => {
    const existingProcess = ProcessStore.getProcess(_id);
    if (existingProcess && !process) {
      setProcess(true);
    }
    ProcessStore.addChangeListener(_id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(_id, handleProcess);
    };
  }, [_id]);

  if (!process)
    return (
      <Typography className={classes.root} variant="body2">
        {member}
      </Typography>
    );

  return (
    <>
      {!isNaN(value) && value > 0 ? (
        <Typography variant="body2" className={classes.percentageLabel}>
          {value}%
        </Typography>
      ) : null}
      <LinearProgress
        classes={{
          root: classes.progress,
          bar: classes.bar
        }}
        {...(!isNaN(value) && value > 0
          ? { variant: "determinate", value }
          : { variant: "indeterminate" })}
      />
    </>
  );
}

Member.propTypes = {
  member: PropTypes.string.isRequired,
  _id: PropTypes.string.isRequired,
  getter: PropTypes.func.isRequired
};
