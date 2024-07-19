import React from "react";
import PropTypes from "prop-types";
import LinearProgress from "@material-ui/core/LinearProgress";
import IconButton from "@material-ui/core/IconButton";
import Tooltip from "@material-ui/core/Tooltip";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import Close from "@material-ui/icons/Close";
import clsx from "clsx";
import { Typography } from "@material-ui/core";

const useStyles = makeStyles(theme => ({
  column: {
    width: "40% !important"
  },
  progress: {
    height: "20px",
    backgroundColor: theme.palette.REY,
    display: "inline-block",
    width: "80%",
    verticalAlign: "middle",
    margin: "30px 0",
    "&.templates": {
      margin: 0
    }
  },
  percentageLabel: {
    color: theme.palette.JANGO,
    fontSize: theme.typography.pxToRem(12),
    paddingRight: theme.spacing(1),
    display: "inline-block"
  },
  bar: {
    backgroundColor: theme.palette.OBI
  },
  button: {
    padding: theme.spacing(1)
  },
  icon: {
    color: theme.palette.JANGO,
    fontSize: theme.typography.pxToRem(20)
  },
  inlineBlock: {
    display: "inline-block",
    width: "50%",
    verticalAlign: "top",
    height: "100%"
  }
}));

//   TODO: interminate progress bar?
export default function UploadProgress({
  value,
  name,
  id,
  cancelFunction,
  renderColumn,
  customClass,
  showLabel
}) {
  const classes = useStyles();
  const progressComponent = (
    <>
      {showLabel && value ? (
        <Typography variant="body2" className={classes.percentageLabel}>
          {value}%
        </Typography>
      ) : null}
      {value ? (
        <LinearProgress
          classes={{
            root: clsx(classes.progress, customClass),
            bar: classes.bar
          }}
          variant="determinate"
          value={value}
        />
      ) : null}
      {name === "upload" && id && cancelFunction ? (
        <Tooltip placement="top" title={<FormattedMessage id="cancelUpload" />}>
          <IconButton className={classes.button} onClick={cancelFunction}>
            <Close className={classes.icon} />
          </IconButton>
        </Tooltip>
      ) : null}
    </>
  );
  if (renderColumn)
    return <td className={classes.column}>{progressComponent}</td>;
  return <div className={classes.inlineBlock}>{progressComponent}</div>;
}

UploadProgress.propTypes = {
  value: PropTypes.number,
  name: PropTypes.string,
  id: PropTypes.string,
  cancelFunction: PropTypes.func,
  renderColumn: PropTypes.bool,
  showLabel: PropTypes.bool,
  customClass: PropTypes.string
};

UploadProgress.defaultProps = {
  value: NaN,
  name: "",
  id: "",
  cancelFunction: null,
  renderColumn: true,
  customClass: "",
  showLabel: false
};
