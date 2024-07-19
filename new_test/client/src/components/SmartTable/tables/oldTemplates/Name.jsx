import React, { useState, useRef, useEffect, useLayoutEffect } from "react";
import PropTypes from "prop-types";
import { makeStyles } from "@material-ui/core/styles";
import Box from "@material-ui/core/Box";
import Typography from "@material-ui/core/Typography";
import TableEditField from "../../TableEditField";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import drawingSVG from "../../../../assets/images/icons/drawing.svg";
import UploadProgress from "../files/innerComponents/UploadProgress";
import FilesListStore, {
  ENTITY_RENAME_MODE
} from "../../../../stores/FilesListStore";
import ProcessStore from "../../../../stores/ProcessStore";
import * as ProcessConstants from "../../../../constants/ProcessContants";

const useStyles = makeStyles(theme => ({
  root: {
    paddingLeft: theme.spacing(3),
    display: "flex",
    "& .shortcuts_wrapper": {
      width: "50%"
    }
  },
  icon: {
    height: "32px",
    width: "36px",
    display: "inline-block",
    verticalAlign: "middle",
    marginRight: theme.spacing(1)
  },
  text: {
    marginTop: "6px",
    display: "inline-block",
    fontSize: theme.typography.pxToRem(14)
  },
  progressBlock: {
    width: "30vw",
    left: "50vw",
    position: "absolute",
    marginTop: "7px"
  }
}));

export default function Name({ name, _id }) {
  const classes = useStyles();
  const [id, setId] = useState(_id);
  const [isTooltipToShow, setTooltip] = useState(false);
  const [isRenameMode, setRenameMode] = useState(false);
  const [process, setProcess] = useState(false);
  const widthFixDiv = useRef();
  const nameSpan = useRef();

  const checkTooltip = () => {
    let newTooltip = false;
    if (nameSpan.current.offsetWidth > widthFixDiv.current.offsetWidth) {
      newTooltip = true;
    }
    if (newTooltip !== isTooltipToShow) {
      setTooltip(newTooltip);
    }
  };

  const renameListener = mode => {
    setRenameMode(mode);
  };

  useEffect(() => {
    setId(_id);
  }, [_id]);

  useLayoutEffect(checkTooltip, []);

  useEffect(checkTooltip, [name]);

  useEffect(() => {
    FilesListStore.addEventListener(
      `${ENTITY_RENAME_MODE}${id}`,
      renameListener
    );
    return () => {
      FilesListStore.removeEventListener(
        `${ENTITY_RENAME_MODE}${id}`,
        renameListener
      );
    };
  }, [id]);

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
    ProcessStore.addChangeListener(id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(id, handleProcess);
    };
  }, [id]);

  return (
    <Box className={classes.root}>
      <img className={classes.icon} src={drawingSVG} alt={name} />
      {isRenameMode ? (
        <TableEditField
          fieldName="name"
          value={name}
          id={id}
          type="oldTemplate"
          extensionEdit
        />
      ) : (
        <SmartTooltip forcedOpen={isTooltipToShow} placement="top" title={name}>
          <Box
            ref={widthFixDiv}
            data-component="objectName"
            data-text={name}
            className={classes.text}
          >
            <Typography component="span" ref={nameSpan}>
              {name}
            </Typography>
          </Box>
        </SmartTooltip>
      )}
      {process && Object.keys(process).length > 0 ? (
        <Box className={classes.progressBlock}>
          <UploadProgress
            customClass="templates"
            renderColumn={false}
            showLabel
            name={process.type}
            value={process.value}
            id={process.id}
          />
        </Box>
      ) : null}
    </Box>
  );
}

Name.propTypes = {
  name: PropTypes.string.isRequired,
  _id: PropTypes.string.isRequired,
  processes: PropTypes.shape({
    [PropTypes.string]: PropTypes.shape({
      name: PropTypes.string,
      type: PropTypes.string,
      id: PropTypes.string
    })
  })
};

Name.defaultProps = {
  processes: {}
};
