import React, { useEffect, useState, useContext } from "react";
import PropTypes from "prop-types";
import { makeStyles, createStyles } from "@material-ui/core/styles";
import Box from "@material-ui/core/Box";
import FileManyCols from "./innerComponents/FileManyCols";
import FileOneCol from "./innerComponents/FileOneCol";
import Folder from "./innerComponents/Folder";
import FilesListStore, {
  ENTITY_RENAME_MODE
} from "../../../../stores/FilesListStore";
import MainFunctions from "../../../../libraries/MainFunctions";
import applicationStore from "../../../../stores/ApplicationStore";
import SmartTableStore from "../../../../stores/SmartTableStore";
import SmartTableActions from "../../../../actions/SmartTableActions";
import ContextMenuStore from "../../../../stores/ContextMenuStore";
import * as ProcessConstants from "../../../../constants/ProcessContants";
import ProcessStore from "../../../../stores/ProcessStore";
import ModalActions from "../../../../actions/ModalActions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import SmartGridContext from "../../../../context/SmartGridContext";

const useStyles = makeStyles(theme =>
  createStyles({
    root: {
      backgroundColor: ({ selected }) => (selected ? "red" : "#f0f0f0"),
      color: theme.palette.DARK,
      padding: "5px",
      margin: "5px",
      borderRadius: "4px",
      border: `1px solid ${theme.palette.BORDER_COLOR}`
    }
  })
);

export default function FileEntity({
  data,
  countOfColumns,
  width,
  selected,
  showMenu,
  gridId
}) {
  const classes = useStyles({ countOfColumns, selected });

  const {
    renameMode,
    openFlag,
    name,
    _id,
    type,
    storage,
    accountId,
    mimeType,
    isShortcut,
    shortcutInfo,
    processId,
    permissions = {}
  } = data;

  const [id, setId] = useState(_id);
  const [process, setProcess] = useState(false);
  const [openLink, setOpenLink] = useState("");
  // const [isTooltipToShow, setTooltip] = useState(false);

  const { interactionsBlockDueScroll } = useContext(SmartGridContext);

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

  const calculateAccess = () =>
    openFlag && !renameMode && (!process || !Object.keys(process).length);

  const [isAccessible, setAccessible] = useState(calculateAccess);

  const onLinkClick = e => {
    if (interactionsBlockDueScroll) return;

    if (ContextMenuStore.getCurrentInfo().isVisible) return;

    const isTrashNow = location.pathname.includes("trash");

    if (isTrashNow) return;

    if (e.button === 0) {
      e.preventDefault();
      if (SmartTableStore.getSelectedRows(gridId).length > 0) {
        const alreadySelectedCells = SmartTableStore.getSelectedRows(gridId);
        SmartTableActions.deselectRows(gridId, alreadySelectedCells);

        return;
      }
      // if (isAccessible) {
      FilesListStore.open(_id, storage, accountId, false, {
        type,
        name,
        mimeType,
        isShortcut,
        shortcutInfo
      });
      // TODO: reset smartGrid measurer cache there
      // }
    }
  };

  useEffect(() => {
    ProcessStore.addChangeListener(processId || id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(processId || id, handleProcess);
    };
  }, [id, data]);

  useEffect(() => {
    setProcess(false);
    setId(_id);
  }, [_id]);

  useEffect(() => {
    const { storageType, storageId, objectId } =
      MainFunctions.parseObjectId(_id);

    if (isAccessible) {
      if (type === "folder") {
        setOpenLink(
          `${applicationStore.getApplicationSetting("UIPrefix")}files/${
            storage || storageType
          }/${accountId || storageId}/${objectId}`
        );
      }
      const app = UserInfoStore.findApp(
        MainFunctions.getExtensionFromName(name),
        mimeType
      );
      if (app === "xenon") {
        setOpenLink(
          `${applicationStore.getApplicationSetting("UIPrefix")}file/${
            storage || storageType
          }+${accountId || storageId}+${objectId}`
        );
      }
    } else setOpenLink("");
  }, [data]);

  useEffect(() => {
    setAccessible(calculateAccess());
  }, [data]);

  const renameListener = () => {
    ModalActions.renameEntity(_id, name, type);
  };

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

  useEffect(() => {
    FilesListStore.addEventListener(
      `${ENTITY_RENAME_MODE}${_id}`,
      renameListener
    );

    return () => {
      FilesListStore.removeEventListener(
        `${ENTITY_RENAME_MODE}${_id}`,
        renameListener
      );
    };
  }, [_id, name]);

  let content = null;

  if (type === "folder")
    content = (
      <Folder
        data={data}
        showMenu={showMenu}
        openLink={openLink}
        onLinkClick={onLinkClick}
        process={process}
        isSharingAllowed={isSharingAllowed}
        interactionsBlockDueScroll={interactionsBlockDueScroll}
      />
    );
  else
    content =
      countOfColumns > 1 ? (
        <FileManyCols
          data={data}
          width={width}
          showMenu={showMenu}
          openLink={openLink}
          onLinkClick={onLinkClick}
          countOfColumns={countOfColumns}
          process={process}
          isSharingAllowed={isSharingAllowed}
          interactionsBlockDueScroll={interactionsBlockDueScroll}
        />
      ) : (
        <FileOneCol
          data={data}
          width={width}
          showMenu={showMenu}
          openLink={openLink}
          onLinkClick={onLinkClick}
          countOfColumns={countOfColumns}
          process={process}
          isSharingAllowed={isSharingAllowed}
          interactionsBlockDueScroll={interactionsBlockDueScroll}
        />
      );

  return <Box className={classes.root}>{content}</Box>;
}

FileEntity.propTypes = {
  data: PropTypes.shape({
    _id: PropTypes.string,
    name: PropTypes.string,
    updateDate: PropTypes.number,
    creationDate: PropTypes.number,
    thumbnail: PropTypes.string,
    type: PropTypes.string,
    mimeType: PropTypes.string,
    isShortcut: PropTypes.bool,
    shortcutInfo: PropTypes.shape({
      targetId: PropTypes.string,
      type: PropTypes.string,
      mimeType: PropTypes.string
    }),
    public: PropTypes.bool,
    isShared: PropTypes.bool,
    icon: PropTypes.string,
    renameMode: PropTypes.bool,
    openFlag: PropTypes.bool,
    storage: PropTypes.string,
    accountId: PropTypes.string,
    processId: PropTypes.string,
    permissions: PropTypes.shape({
      canViewPublicLink: PropTypes.bool
    }).isRequired
  }).isRequired,
  countOfColumns: PropTypes.number.isRequired,
  width: PropTypes.number.isRequired,
  gridId: PropTypes.string.isRequired,
  selected: PropTypes.bool,
  showMenu: PropTypes.func.isRequired
};

FileEntity.defaultProps = {
  selected: false
};
