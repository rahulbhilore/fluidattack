import React from "react";
import PropTypes from "prop-types";
import Tooltip from "@material-ui/core/Tooltip";
import Button from "@material-ui/core/Button";
import { makeStyles } from "@material-ui/core/styles";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import SVG from "react-inlinesvg";
import ModalActions from "../../../../actions/ModalActions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListStore from "../../../../stores/FilesListStore";
import permissionsSVG from "../../../../assets/images/permissions.svg";
import publicLinkSVG from "../../../../assets/images/publicLink.svg";

const useStyles = makeStyles(theme => ({
  button: {
    height: "36px",
    width: "36px",
    backgroundColor: "transparent",
    border: `1px solid ${theme.palette.GREY_TEXT}`,
    minWidth: "36px",
    "&:hover svg > .st0": {
      fill: theme.palette.LIGHT
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
  }
}));

function Access(props) {
  const { type, name, filename, mimeType, id, processes, permissions } = props;
  const classes = useStyles();
  /* eslint-disable */
  const isPublic = props.public;
  /* eslint-enable */

  if (Object.keys(processes).length > 0) {
    // should we use multiprocessing as well?
    const singleProcess = Object.values(processes)[0];
    return (
      <td className="process_percentage">
        <span className={`process_caption ${singleProcess.name}`}>
          {singleProcess.value && !_.isNaN(parseFloat(singleProcess.value)) ? (
            `${
              parseFloat(singleProcess.value) > 99.99
                ? 99.99
                : parseFloat(singleProcess.value)
            }%`
          ) : (
            <FormattedMessage id={singleProcess.name} />
          )}
        </span>
      </td>
    );
  }

  const { storageType } = MainFunctions.parseObjectId(id);
  const storage = MainFunctions.storageCodeToServiceName(storageType);
  const isSupportedFile =
    UserInfoStore.findApp(
      MainFunctions.getExtensionFromName(name || filename),
      mimeType
    ) === "xenon";
  let isSharingAllowed =
    (FilesListStore.getCurrentState() !== "trash" &&
      UserInfoStore.isFeatureAllowedByStorage(storage, "share", type)) ||
    (isSupportedFile &&
      UserInfoStore.isFeatureAllowedByStorage(storage, "share", "publicLink"));
  if (
    Object.prototype.hasOwnProperty.call(permissions, "canManagePermissions")
  ) {
    isSharingAllowed = permissions.canManagePermissions;
    if (
      isSupportedFile &&
      Object.prototype.hasOwnProperty.call(
        permissions,
        "canManagePublicLink"
      ) &&
      permissions.canManagePublicLink === true
    ) {
      isSharingAllowed = true;
    }
  }

  let isPublicAccessAvailable = isPublic === true;
  const userOptions = UserInfoStore.getUserInfo("options");
  if (userOptions.sharedLinks === false) {
    isPublicAccessAvailable = false;
  }
  const openPermissionsDialog = () => {
    ModalActions.shareManagement(id);
  };
  return (
    <td className="access">
      {isPublicAccessAvailable ? (
        <img
          className={classes.publicIcon}
          src={publicLinkSVG}
          alt="View only link available"
        />
      ) : (
        <div className="publicIconPlaceholder" />
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
          >
            <SVG src={permissionsSVG}>
              <img src={permissionsSVG} alt="permissions" />
            </SVG>
          </Button>
        </Tooltip>
      ) : null}
    </td>
  );
}

Access.propTypes = {
  processes: PropTypes.shape({
    [PropTypes.string]: PropTypes.shape({
      name: PropTypes.string,
      type: PropTypes.string,
      id: PropTypes.string
    })
  }),
  public: PropTypes.bool,
  type: PropTypes.string.isRequired,
  name: PropTypes.string,
  filename: PropTypes.string,
  mimeType: PropTypes.string,
  id: PropTypes.string.isRequired,
  permissions: PropTypes.shape({
    canManagePermissions: PropTypes.bool,
    canManagePublicLink: PropTypes.bool
  })
};

Access.defaultProps = {
  processes: {},
  public: false,
  name: "",
  filename: "",
  mimeType: "",
  permissions: {
    canManagePermissions: false,
    canManagePublicLink: false
  }
};

export default Access;
