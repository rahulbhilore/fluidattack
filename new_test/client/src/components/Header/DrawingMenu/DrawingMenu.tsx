import { Divider, Menu, menuClasses, MenuItem, Tooltip } from "@mui/material";
import React, {
  SyntheticEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from "react";
import _ from "underscore";
import ApplicationActions from "../../../actions/ApplicationActions";
import ApplicationStore, {
  DRAWING_MENU_SWITCHED
} from "../../../stores/ApplicationStore";
import ChangeEditorUrlButton from "../SpecificHeader/FileHeader/ChangeEditorUrlButton";
import CommentsButton from "../SpecificHeader/FileHeader/CommentsButton";
import OpenInCommander from "../SpecificHeader/FileHeader/OpenInCommander";
import PermissionsDialogButton from "../SpecificHeader/FileHeader/PermissionsDialogButton";
import UpgradeSessionButton from "../SpecificHeader/FileHeader/UpgradeSessionButton";
import VersionControlButton from "../SpecificHeader/FileHeader/VersionControlButton";
import DrawingMenuItem from "./DrawingMenuItem";
import useTranslate from "../../../hooks/useTranslate";
import saveAsSVG from "../../../assets/images/header/save_as.svg";
import downloadDwgSVG from "../../../assets/images/header/download_dwg_16.svg";
import downloadDxfSVG from "../../../assets/images/header/download_dxf_16.svg";
import downloadPdfSVG from "../../../assets/images/header/download_pdf_16.svg";
import saveAsPDFInStorageSVG from "../../../assets/images/header/pdf_to_storage.svg";
import optionsSVG from "../../../assets/images/header/options.svg";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import userInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListStore from "../../../stores/FilesListStore";
import FilesListActions from "../../../actions/FilesListActions";
import {
  StorageType,
  StorageValues,
  UserStoragesInfo
} from "../../../types/StorageTypes";

type Permissions = {
  canManagePermissions: boolean;
  canViewPermissions: boolean;
  canViewPublicLink: boolean;
  canManagePublicLink: boolean;
};

type PropType = {
  currentFile: {
    _id: string;
    folderId: string;
    isOwner?: boolean;
    name: string;
    shared: boolean;
    viewFlag: boolean;
    viewOnly: boolean;
    permissions: Permissions;
  };
  notificationBadge: number;
  onCommentButtonClick: (event: SyntheticEvent) => void;
  isMobile?: boolean;
};

export default function DrawingMenu({
  currentFile,
  notificationBadge,
  onCommentButtonClick,
  isMobile = true
}: PropType) {
  const [anchorEl, setAnchorEl] = useState<Element | null>(null);

  const isTokenAccess = !!MainFunctions.QueryString("token");
  const isMobileDevice = MainFunctions.isMobileDevice();

  const {
    _id,
    folderId,
    isOwner,
    name,
    permissions = {} as Permissions,
    shared,
    viewFlag,
    viewOnly
  } = currentFile;

  const hasAnyPermission = useMemo(
    () =>
      Object.values(permissions).some(permission => permission) ||
      Boolean(isOwner) ||
      Boolean(shared),
    [permissions, isOwner, shared]
  );

  const drawingMenuSwitched = () => {
    const menuState = ApplicationStore.getDrawingMenuState();

    if (!menuState) setAnchorEl(null);
    else {
      XenonConnectionActions.postMessage({ messageName: "FL_DROPDOWN_OPENED" });
      setAnchorEl(document.getElementById("temp-logo-id"));
    }
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(
      DRAWING_MENU_SWITCHED,
      drawingMenuSwitched
    );
    return () => {
      ApplicationStore.removeChangeListener(
        DRAWING_MENU_SWITCHED,
        drawingMenuSwitched
      );
    };
  }, []);

  const menuClose = useCallback(() => {
    ApplicationActions.switchDrawingMenu(false);
  }, []);

  const onMenuItemClick = () => {
    ApplicationActions.switchDrawingMenu(false);
  };

  const openSaveAsDialog = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "saveas" });
    menuClose();
  }, []);

  const downloadDWG = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadDwg" });
    menuClose();
  }, []);
  const downloadDXF = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadDxf" });
    menuClose();
  }, []);
  const downloadPDF = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadPdf" });
    menuClose();
  }, []);
  const saveAsPDFInStorage = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "saveasPdf" });
    menuClose();
  }, []);
  const openFileOptions = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "option" });
    menuClose();
  }, []);
  const [isOldUIMode, setOldUIMode] = useState(
    userInfoStore.getUserInfo("preferences")?.useOldUI || false
  );

  const onInfoUpdate = useCallback(() => {
    setOldUIMode(userInfoStore.getUserInfo("preferences")?.useOldUI || false);
  }, []);

  useEffect(() => {
    userInfoStore.addChangeListener(INFO_UPDATE, onInfoUpdate);
    return () => {
      userInfoStore.removeChangeListener(INFO_UPDATE, onInfoUpdate);
    };
  }, []);

  const { t } = useTranslate();
  const [path, setPath] = useState<string[]>([]);

  useEffect(() => {
    const { storageType, storageId } = FilesListStore.findCurrentStorage();
    if (currentFile.folderId) {
      const properParentId = MainFunctions.encapsulateObjectId(
        storageType,
        storageId,
        currentFile.folderId
      );
      FilesListActions.loadPath(properParentId).then(loadedPath => {
        const storageServiceName =
          MainFunctions.storageCodeToServiceName(storageType);
        const storageName =
          MainFunctions.serviceStorageNameToEndUser(storageServiceName);
        const userInfo = userInfoStore.getStoragesInfo() as UserStoragesInfo;
        const storageValues = (_.find(
          userInfo[storageServiceName as StorageType],
          storageObject =>
            storageObject[
              `${storageServiceName}_id` as keyof typeof storageObject
            ] === storageId
        ) ?? {}) as StorageValues<StorageType>;
        const accountName =
          storageValues[
            `${storageServiceName}_username` as keyof typeof storageValues
          ];
        const pathToShow = [storageName, accountName].concat(
          loadedPath.map(
            (pathObject: { name: string; [key: string]: string }) =>
              pathObject.name
          )
        );
        setPath(pathToShow);
      });
    }
  }, [currentFile]);

  if (!_id || !name) return null;

  if (!isOldUIMode) {
    return (
      <Menu
        id="drawing-menu-header"
        anchorEl={anchorEl}
        elevation={0}
        open={Boolean(anchorEl)}
        anchorOrigin={{
          vertical: "bottom",
          horizontal: "left"
        }}
        transformOrigin={{
          vertical: "top",
          horizontal: "left"
        }}
        onClose={menuClose}
        sx={{
          [`& .${menuClasses.paper}`]: {
            bgcolor: theme => theme.palette.JANGO,
            padding: 0,
            minWidth: 245
          },
          [`& .${menuClasses.list}`]: {
            padding: 0
          }
        }}
        MenuListProps={{
          // @ts-ignore Required for tests
          "data-component": "drawing-menu-list"
        }}
      >
        {isMobile && (
          <Tooltip
            placement="right"
            title={path.length > 0 ? `${path.join("\\")}\\${name}` : name}
          >
            <MenuItem
              sx={{
                borderRadius: 0,
                height: 36,
                minHeight: 36,
                color: theme => theme.palette.REY,
                bgcolor: theme => theme.palette.drawingMenu.bg,
                cursor: "default",
                "&:hover,&:focus,&:active": {
                  bgcolor: theme => theme.palette.drawingMenu.bg
                }
              }}
            >
              {MainFunctions.shrinkString(currentFile.name, 20)}
            </MenuItem>
          </Tooltip>
        )}
        {isMobile && (
          <Divider sx={{ my: `0 !important`, borderBottomColor: "#1E2023" }} />
        )}
        {/* `isMobile` prop both checks screen size and if user has an actual mobile device */}
        {/* For here, we should just check user's mobile device. That's why `isMobileDevice` used instead of `isMobile` */}
        {!isMobileDevice && (!isTokenAccess || !currentFile.viewFlag) ? (
          <OpenInCommander
            id={_id}
            folderId={folderId}
            name={name}
            isForMobileHeader
            onMenuItemClick={onMenuItemClick}
          />
        ) : null}
        {!currentFile.viewFlag && (
          <>
            <Divider
              sx={{ my: `0 !important`, borderBottomColor: "#1E2023" }}
            />
            <DrawingMenuItem
              onClick={openSaveAsDialog}
              caption={t("quickAccessBar.saveAs")}
              icon={saveAsSVG}
              dataComponent="drawing-menu-save-as"
            />
            <DrawingMenuItem
              onClick={saveAsPDFInStorage}
              caption={t("quickAccessBar.saveAsPDF")}
              icon={saveAsPDFInStorageSVG}
              dataComponent="drawing-menu-save-pdf-in-storage"
            />
            <Divider
              sx={{ my: `0 !important`, borderBottomColor: "#1E2023" }}
            />
            <DrawingMenuItem
              onClick={downloadDWG}
              caption={t("quickAccessBar.downloadDWG")}
              icon={downloadDwgSVG}
              dataComponent="drawing-menu-download-dwg"
            />
            <DrawingMenuItem
              onClick={downloadDXF}
              caption={t("quickAccessBar.downloadDXF")}
              icon={downloadDxfSVG}
              dataComponent="drawing-menu-download-dxf"
            />
            <DrawingMenuItem
              onClick={downloadPDF}
              caption={t("quickAccessBar.downloadPDF")}
              icon={downloadPdfSVG}
              dataComponent="drawing-menu-download-pdf"
            />
            <Divider
              sx={{ my: `0 !important`, borderBottomColor: "#1E2023" }}
            />
            <DrawingMenuItem
              onClick={openFileOptions}
              caption={t("quickAccessBar.options")}
              icon={optionsSVG}
              dataComponent="drawing-menu-options"
            />
          </>
        )}
      </Menu>
    );
  }
  return (
    <Menu
      id="drawing-menu-header"
      anchorEl={anchorEl}
      elevation={0}
      open={Boolean(anchorEl)}
      anchorOrigin={{
        vertical: "bottom",
        horizontal: "left"
      }}
      transformOrigin={{
        vertical: "top",
        horizontal: "left"
      }}
      onClose={menuClose}
      sx={{
        [`& .${menuClasses.paper}`]: {
          bgcolor: theme => theme.palette.JANGO,
          padding: 0,
          minWidth: 245
        },
        [`& .${menuClasses.list}`]: {
          padding: 0
        }
      }}
    >
      <OpenInCommander
        id={_id}
        folderId={folderId}
        name={name}
        isForMobileHeader
        onMenuItemClick={onMenuItemClick}
      />
      <UpgradeSessionButton
        viewFlag={viewFlag}
        isForMobileHeader
        onMenuItemClick={onMenuItemClick}
      />
      <CommentsButton
        notificationBadge={notificationBadge}
        onCommentButtonClick={onCommentButtonClick}
        isForMobileHeader
        onMenuItemClick={onMenuItemClick}
      />
      <ChangeEditorUrlButton
        isForMobileHeader
        onMenuItemClick={onMenuItemClick}
      />
      {hasAnyPermission && (
        <PermissionsDialogButton
          fileId={_id}
          name={name}
          isForMobileHeader
          onMenuItemClick={onMenuItemClick}
        />
      )}
      <VersionControlButton
        _id={_id}
        folderId={folderId}
        filename={name}
        viewOnly={viewOnly}
        isForMobileHeader
        onMenuItemClick={onMenuItemClick}
      />
    </Menu>
  );
}
