import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Link } from "react-router";
import PropTypes from "prop-types";
import clsx from "clsx";
import { makeStyles } from "@material-ui/core/styles";
import Box from "@material-ui/core/Box";
import { Tooltip, Typography } from "@material-ui/core";
import TableEditField from "../../../TableEditField";
import FilesListActions from "../../../../../actions/FilesListActions";
import FilesListStore from "../../../../../stores/FilesListStore";
import MainFunctions from "../../../../../libraries/MainFunctions";
import applicationStore from "../../../../../stores/ApplicationStore";
import userInfoStore from "../../../../../stores/UserInfoStore";
import ProcessStore from "../../../../../stores/ProcessStore";
import SmartTableStore, {
  OPEN_EVENT
} from "../../../../../stores/SmartTableStore";
import * as ProcessConstants from "../../../../../constants/ProcessContants";
import Highlight from "./Highlight";
import Processes from "../../../../../constants/appConstants/Processes";

const useStyles = makeStyles(theme => ({
  root: {
    padding: "0 10px 0 88px",
    width: "100%",
    display: "inline-block"
  },
  accessible: {
    "& span:hover": {
      textDecoration: "underline"
    }
  },
  xref: {
    padding: "0 10px 0 45px"
  },
  anchor: {
    lineHeight: "80px"
  },
  link: {
    color: theme.palette.VADER,
    cursor: "pointer",
    display: "inline-block",
    transitionDuration: "0.12s",
    verticalAlign: "top",
    lineHeight: "80px",
    "&.xref": {
      lineHeight: "38px",
      "&.isSelected": {
        color: theme.palette.LIGHT
      }
    }
  }
}));

const NON_BLOCKING_PROCESSES = [
  Processes.UPLOAD_COMPLETE,
  Processes.CREATE_COMPLETED
];

function NameBlock({
  renameMode,
  openFlag,
  name,
  _id,
  type,
  isShortcut,
  shortcutInfo,
  storage,
  accountId,
  mimeType,
  processId,
  tableId,
  highlightQuery
}) {
  const classes = useStyles();
  const [id, setId] = useState(_id);
  const [openLink, setOpenLink] = useState(null);
  const [isTooltipToShow, setTooltip] = useState(false);
  const [process, setProcess] = useState(false);

  // Basically true if no process or process is "decorative"
  // e.g. UPLOAD_COMPLETE
  const isNonBlockingProcess = () => {
    if (!process || !Object.keys(process).length) return true;
    if (process?.type && NON_BLOCKING_PROCESSES.includes(process.type))
      return true;
    return false;
  };

  const calculateAccess = () =>
    openFlag &&
    !_id?.startsWith("CS_") &&
    !renameMode &&
    isNonBlockingProcess();

  const [isAccessible, setAccessible] = useState(calculateAccess);

  const widthFixDiv = useRef();
  const nameSpan = useRef();

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

  const onLinkClick = e => {
    if (!isNonBlockingProcess()) return;

    if (e && e.button !== 0) return;

    e?.preventDefault();

    if (isAccessible) {
      FilesListStore.open(_id, storage, accountId, false, {
        shortcutInfo,
        isShortcut,
        type,
        name,
        mimeType
      });
    }
  };

  // eslint-disable-next-line arrow-body-style
  useEffect(() => {
    return () => {
      // remove rename on unmount
      const proc = ProcessStore.getProcess(id);
      if (proc && proc.type === Processes.RENAME) {
        FilesListActions.setEntityRenameMode(id, false);
      }
    };
  }, []);

  useEffect(() => {
    SmartTableStore.addListener(OPEN_EVENT + tableId + _id, onLinkClick);
    return () => {
      SmartTableStore.removeListener(OPEN_EVENT + tableId + _id, onLinkClick);
    };
  }, [id, process]);

  useEffect(() => {
    ProcessStore.addChangeListener(processId || _id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(processId || _id, handleProcess);
    };
  }, [id, processId]);

  useEffect(() => {
    setProcess(false);
    setId(_id);
  }, [_id]);

  useEffect(() => {
    const { storageType, storageId, objectId } =
      MainFunctions.parseObjectId(_id);
    if (isAccessible) {
      // target is shortcut
      if (isShortcut) {
        const isFolderShortcut = shortcutInfo.type === "folder";
        if (
          isFolderShortcut ||
          userInfoStore.findApp(
            MainFunctions.getExtensionFromName(shortcutInfo.mimeType),
            mimeType
          ) === "xenon"
        ) {
          setOpenLink(
            `${applicationStore.getApplicationSetting("UIPrefix")}${
              isFolderShortcut ? "files" : "file"
            }/${
              isFolderShortcut
                ? shortcutInfo.targetId.replaceAll("+", "/")
                : shortcutInfo.targetId
            }`
          );
        }
      }

      // target is folder
      else if (type === "folder") {
        setOpenLink(
          `${applicationStore.getApplicationSetting("UIPrefix")}files/${
            storage || storageType
          }/${accountId || storageId}/${objectId}`
        );
      }

      // target may be opened in xenon
      else if (
        userInfoStore.findApp(
          MainFunctions.getExtensionFromName(name),
          mimeType
        ) === "xenon"
      ) {
        setOpenLink(
          `${applicationStore.getApplicationSetting("UIPrefix")}file/${
            storage || storageType
          }+${accountId || storageId}+${objectId}`
        );
      }
    } else {
      setOpenLink("");
    }
  }, [_id, type, openFlag, renameMode, processId, name, mimeType]);

  const checkTooltip = () => {
    if (!renameMode) {
      let newTooltip = false;
      if (nameSpan.current.offsetWidth > widthFixDiv.current.offsetWidth) {
        newTooltip = true;
      }
      if (newTooltip !== isTooltipToShow) {
        setTooltip(newTooltip);
      }
    }
  };

  useLayoutEffect(checkTooltip, []);

  useEffect(checkTooltip, [name, renameMode]);

  useEffect(() => {
    setAccessible(calculateAccess());
  }, [openFlag, renameMode, process]);

  const namePart = (
    <Typography className={classes.link} component="span" ref={nameSpan}>
      {highlightQuery ? (
        <Highlight string={name} highlightPart={highlightQuery} />
      ) : (
        name
      )}
    </Typography>
  );
  let nameComponent = !isNonBlockingProcess() ? (
    <Box ref={widthFixDiv}>{namePart}</Box>
  ) : (
    <Box ref={widthFixDiv}>
      <Link to={openLink} onClick={onLinkClick} className={classes.anchor}>
        {namePart}
      </Link>
    </Box>
  );

  if (isTooltipToShow)
    nameComponent = (
      <Tooltip placement="top" title={name}>
        {nameComponent}
      </Tooltip>
    );

  return (
    <Box
      className={clsx(
        classes.root,
        isAccessible ? classes.accessible : null,
        classes[type]
      )}
      data-component="objectName"
      data-text={name}
    >
      {renameMode ? (
        <TableEditField
          fieldName="name"
          value={name}
          id={_id}
          type={type}
          extensionEdit={type === "file" && !isShortcut}
        />
      ) : (
        nameComponent
      )}
    </Box>
  );
}

NameBlock.propTypes = {
  openFlag: PropTypes.bool.isRequired,
  processId: PropTypes.string,
  name: PropTypes.string.isRequired,
  _id: PropTypes.string.isRequired,
  accountId: PropTypes.string,
  storage: PropTypes.string,
  mimeType: PropTypes.string,
  type: PropTypes.string,
  isShortcut: PropTypes.bool,
  shortcutInfo: PropTypes.shape({
    mimeType: PropTypes.string.isRequired,
    type: PropTypes.string.isRequired,
    targetId: PropTypes.string.isRequired
  }),
  renameMode: PropTypes.bool,
  tableId: PropTypes.string.isRequired,
  highlightQuery: PropTypes.string
};

NameBlock.defaultProps = {
  type: "files",
  isShortcut: false,
  shortcutInfo: {
    mimeType: "",
    type: "",
    targetId: ""
  },
  processId: null,
  storage: "",
  accountId: "",
  mimeType: "",
  renameMode: false,
  highlightQuery: null
};

export default React.memo(NameBlock);
