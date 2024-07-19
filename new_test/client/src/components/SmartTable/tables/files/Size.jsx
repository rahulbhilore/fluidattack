import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import UserInfoStore from "../../../../stores/UserInfoStore";
import ProcessStore from "../../../../stores/ProcessStore";
import * as ProcessConstants from "../../../../constants/ProcessContants";

const useStyles = makeStyles(() => ({
  root: {
    fontWeight: "bold"
  }
}));

function Size({ _id, size, data }) {
  const [id, setId] = useState(_id);
  const { processId } = data;
  useEffect(() => {
    setId(_id);
  }, [_id]);
  const { deleted, type } = data;
  const [process, setProcess] = useState(false);

  useEffect(() => {
    setProcess(false);
    setId(_id);
  }, [_id]);

  const handleProcess = processInfo => {
    switch (processInfo.status) {
      case ProcessConstants.STEP:
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
    ProcessStore.addChangeListener(processId || id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(processId || id, handleProcess);
    };
  }, [id, data]);
  const classes = useStyles();

  if (process) return null;
  if (deleted && UserInfoStore.isFeatureAllowedByStorage("inlineTrash")) {
    return null;
  }
  if (type === "file" && parseInt(size || "0", 10) > 0) {
    return <Typography className={classes.root}>{size}</Typography>;
  }
  return null;
}

Size.propTypes = {
  _id: PropTypes.string.isRequired,
  data: PropTypes.shape({
    type: PropTypes.string,
    deleted: PropTypes.bool,
    processId: PropTypes.string
  }).isRequired,
  size: PropTypes.oneOfType([PropTypes.string, PropTypes.number])
};

Size.defaultProps = {
  size: 0
};

export default React.memo(Size, (prevProps, nextProps) => {
  if (
    prevProps._id === nextProps._id &&
    _.isEqual(prevProps.data, nextProps.data)
  )
    return true;
  return false;
});
