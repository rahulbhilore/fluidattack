import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import { Button, Box } from "@material-ui/core";
import Tooltip from "@material-ui/core/Tooltip";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import { makeStyles } from "@material-ui/core/styles";
import SVG from "react-inlinesvg";
import ModalActions from "../../../../actions/ModalActions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListStore from "../../../../stores/FilesListStore";
import ProcessStore from "../../../../stores/ProcessStore";
import permissionsSVG from "../../../../assets/images/permissions.svg";
import publicLinkSVG from "../../../../assets/images/publicLink.svg";
import * as ProcessConstants from "../../../../constants/ProcessContants";
import SmartTableStore from "../../../../stores/SmartTableStore";
import * as SmartTableConstants from "../../../../constants/SmartTableConstants";

const useStyles = makeStyles(theme => ({
  button: {
    height: "36px",
    width: "36px",
    backgroundColor: "transparent",
    border: `1px solid ${theme.palette.GREY_TEXT}`,
    minWidth: "36px",
    "&:hover svg > .st0": {
      fill: theme.palette.LIGHT
    },
    "&:hover": {
      backgroundColor: theme.palette.OBI
    }
  },
  iconSubstitute: {
    display: "inline-block",
    width: "23px"
  },
  publicIcon: {
    marginRight: "5px",
    width: "20px",
    height: "20px",
    verticalAlign: "middle"
  },
  svg: {
    height: "20px"
  },
  process: {
    color: theme.palette.OBI,
    fontSize: theme.typography.pxToRem(12),
    fontWeight: 700,
    textTransform: "uppercase"
  }
}));

function Access({ _id, data }) {
  const [id, setId] = useState(_id);
  const classes = useStyles();
  const [process, setProcess] = useState(false);

  const {
    type,
    name,
    mimeType,
    public: isPublic,
    permissions = {},
    processId
  } = data;

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

  const checkCreateProcess = () => {
    const createProcess = ProcessStore.getProcess(id);
    if (!createProcess || createProcess.type !== "creating") return;

    setProcess(createProcess);
  };

  useEffect(() => {
    SmartTableStore.addListener(
      SmartTableConstants.SORT_COMPLETED,
      checkCreateProcess
    );
    return () => {
      SmartTableStore.removeListener(
        SmartTableConstants.SORT_COMPLETED,
        checkCreateProcess
      );
    };
  });

  useEffect(() => {
    ProcessStore.addChangeListener(processId || id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(processId || id, handleProcess);
    };
  }, [id, data]);

  useEffect(() => {
    setProcess(ProcessStore.getProcess(_id) || false);
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

  if (FilesListStore.getCurrentState() === "trash") return <div />;

  const { storageType } = MainFunctions.parseObjectId(id);
  const storage = MainFunctions.storageCodeToServiceName(storageType);

  let isSharingAllowed =
    FilesListStore.getCurrentState() !== "trash" &&
    (UserInfoStore.isFeatureAllowedByStorage(storage, "share", type) ||
      (UserInfoStore.isFeatureAllowedByStorage(
        storage,
        "share",
        "publicLink"
      ) &&
        UserInfoStore.findApp(
          MainFunctions.getExtensionFromName(name),
          mimeType
        ) === "xenon"));

  if (
    Object.prototype.hasOwnProperty.call(permissions, "canManagePermissions") ||
    Object.prototype.hasOwnProperty.call(permissions, "canViewPublicLink") ||
    Object.prototype.hasOwnProperty.call(permissions, "canManagePublicLink") ||
    Object.prototype.hasOwnProperty.call(permissions, "canViewPermissions")
  ) {
    isSharingAllowed =
      permissions.canManagePermissions ||
      permissions.canViewPermissions ||
      permissions.canViewPublicLink ||
      permissions.canManagePublicLink;
  }

  let isPublicAccessAvailable =
    isPublic === true && permissions.canViewPublicLink;
  const userOptions = UserInfoStore.getUserInfo("options");
  if (userOptions.sharedLinks === false) {
    isPublicAccessAvailable = false;
  }
  const openPermissionsDialog = () => {
    ModalActions.shareManagement(id, name, type);
  };
  return (
    <div>
      {isPublicAccessAvailable ? (
        <img
          className={classes.publicIcon}
          src={publicLinkSVG}
          alt="View only link available"
        />
      ) : (
        <Box className={classes.iconSubstitute} />
      )}
      {isSharingAllowed ? (
        <Tooltip
          id="permissionsForEntityTooltip"
          title={<FormattedMessage id="permissions" />}
          placement="top"
        >
          <Button
            onClick={openPermissionsDialog}
            onTouchEnd={openPermissionsDialog}
            className={classes.button}
            data-component="manage_permissions"
          >
            <SVG src={permissionsSVG} className={classes.svg}>
              <img src={permissionsSVG} alt="permissions" />
            </SVG>
          </Button>
        </Tooltip>
      ) : null}
    </div>
  );
}

Access.propTypes = {
  _id: PropTypes.string.isRequired,
  data: PropTypes.shape({
    type: PropTypes.string,
    name: PropTypes.string,
    mimeType: PropTypes.string,
    public: PropTypes.bool,
    permissions: PropTypes.shape({
      canManagePermissions: PropTypes.bool
    }),
    processId: PropTypes.string
  }).isRequired
};

Access.defaultProps = {
  // process: {}
  // processId: ""
};

export default React.memo(Access, (prevProps, nextProps) => {
  if (
    prevProps._id === nextProps._id &&
    prevProps.processId === nextProps.processId &&
    _.isEqual(prevProps.data, nextProps.data)
  )
    return true;
  return false;
});
