import {
  Box,
  Divider,
  Menu,
  MenuItem,
  Typography,
  menuItemClasses,
  paperClasses,
  styled
} from "@mui/material";
import React, { SyntheticEvent, useCallback, useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import ArrowDropDownIcon from "@mui/icons-material/ArrowDropDown";
import FilesListActions from "../../../../actions/FilesListActions";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListStore from "../../../../stores/FilesListStore";
import UserInfoStore from "../../../../stores/UserInfoStore";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import {
  StorageType,
  StorageValues,
  UserStoragesInfo
} from "../../../../types/StorageTypes";
import useTranslate from "../../../../hooks/useTranslate";
import openInCommanderSVG from "../../../../assets/images/context/openInCommander.svg";
import downloadDwgSVG from "../../../../assets/images/header/download_dwg_16.svg";
import downloadDxfSVG from "../../../../assets/images/header/download_dxf_16.svg";
import downloadPdfSVG from "../../../../assets/images/header/download_pdf_16.svg";
import saveAsPDFInStorageSVG from "../../../../assets/images/header/pdf_to_storage.svg";
import exportSVG from "../../../../assets/images/header/EXPORT.svg";
import saveAsSVG from "../../../../assets/images/header/save_as.svg";
import newtabSVG from "../../../../assets/images/context/newtab.svg";
import { FileEntity } from "../../../ContextMenu/Files";
import ModalActions from "../../../../actions/ModalActions";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";
import XenonConnectionActions from "../../../../actions/XenonConnectionActions";

type PropType = {
  deleted?: boolean;
  deshared?: boolean;
  isViewOnly?: boolean;
  name: string;
  objectId: string;
  parentId?: string;
  currentFile: FileEntity;
};

const imageSize = 16;

const imageStyles = {
  width: `${imageSize}px`,
  height: `${imageSize}px`
};

const StyledMenuItem = styled(MenuItem)(() => ({
  display: "flex",
  gap: "5px",
  transitionDuration: ".25s",
  "&:hover,&:focus,&:active": {
    backgroundColor: "#1e2023 !important"
  }
}));

export default function ObjectName({
  deleted = false,
  deshared = false,
  isViewOnly = false,
  name,
  objectId,
  parentId = "",
  currentFile
}: PropType) {
  const [path, setPath] = useState<string[]>([]);
  const [showTooltip, setTooltip] = useState(false);
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);

  const toggleMenu = useCallback((event: SyntheticEvent) => {
    setAnchorEl(prev => {
      if (prev) {
        return null;
      }

      XenonConnectionActions.postMessage({ messageName: "FL_DROPDOWN_OPENED" });
      return event.currentTarget as HTMLElement;
    });
    setTooltip(false);
  }, []);

  useEffect(() => {
    const { storageType, storageId } = FilesListStore.findCurrentStorage();
    if (parentId) {
      const properParentId = MainFunctions.encapsulateObjectId(
        storageType,
        storageId,
        parentId
      );
      FilesListActions.loadPath(properParentId).then(loadedPath => {
        const storageServiceName =
          MainFunctions.storageCodeToServiceName(storageType);
        const storageName =
          MainFunctions.serviceStorageNameToEndUser(storageServiceName);
        const userInfo = UserInfoStore.getStoragesInfo() as UserStoragesInfo;
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
  }, [objectId]); // will trigger only if objectId has changed

  const openTooltip = () => {
    setTooltip(true);
  };
  const closeTooltip = () => {
    setTooltip(false);
  };

  const hideMenu = useCallback(() => {
    setAnchorEl(null);
  }, []);

  const { t } = useTranslate();

  const openSaveAsDialog = useCallback(() => {
    hideMenu();
    ModalActions.saveAs(FilesListStore.getCurrentFile()._id, [], 0);
  }, []);

  const openFileInNewWindow = useCallback(() => {
    hideMenu();

    window.open(`/file/${currentFile._id}`, "_blank", "noopener,noreferrer");
  }, [currentFile]);

  const openInCommander = useCallback(() => {
    hideMenu();
    FilesListActions.openFileInCommander(currentFile._id, currentFile.parent, {
      name: currentFile.filename
    })
      .then(() => {
        SnackbarUtils.alertOk({ id: "acLaunched" });
      })
      .catch(error => {
        ModalActions.openInCommander();
      });
  }, [currentFile]);

  const downloadDWG = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadDwg" });
  }, []);
  const downloadDXF = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadDxf" });
  }, []);
  const downloadPDF = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadPdf" });
  }, []);
  const download = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "downloadDwg" });
  }, []);
  const exportFile = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "exportPdf" });
  }, []);
  const saveAsPDFInStorage = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "saveasPdf" });
  }, []);

  return (
    <>
      <SmartTooltip
        placement="bottom"
        forcedOpen={showTooltip}
        title={path.length > 0 ? `${path.join("\\")}\\${name}` : name}
      >
        <Box
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            height: "28px",
            lineHeight: "28px",
            border: theme => `1px solid ${theme.palette.drawingMenu.bg}`,
            borderRadius: "4px",
            cursor: "pointer",
            color: theme => theme.palette.WHITE_HEADER_NAME,
            minWidth: "200px",
            p: "6px",
            "&:hover,&:focus,&:active": {
              borderColor: theme => theme.palette.CLONE
            }
          }}
          onMouseEnter={openTooltip}
          onMouseLeave={closeTooltip}
          onClick={toggleMenu}
        >
          <Typography
            data-component="file_name"
            data-text={name}
            variant="body2"
            sx={{
              fontSize: theme => theme.typography.pxToRem(14)
            }}
          >
            {`${MainFunctions.shrinkString(name, 40)} `}
            {/* Irreplaceable - can be updated by DrawingLoader due to different reasons */}
            {deshared && <FormattedMessage id="Deshared" />}
            {deleted && <FormattedMessage id="Deleted" />}
            {!deleted && !deshared && isViewOnly && (
              <FormattedMessage id="ViewOnly" />
            )}
          </Typography>
          <ArrowDropDownIcon sx={{ color: "#ffffff" }} />
        </Box>
      </SmartTooltip>

      <Menu
        id="menu-file"
        anchorEl={anchorEl}
        elevation={0}
        keepMounted
        open={Boolean(anchorEl)}
        anchorOrigin={{
          vertical: "bottom",
          horizontal: "left"
        }}
        transformOrigin={{ vertical: "top", horizontal: "left" }}
        onClose={hideMenu}
        sx={{
          [`& .${paperClasses.root}`]: {
            minWidth: anchorEl?.clientWidth,
            bgcolor: "#3d3d3d",
            [`& .${menuItemClasses.root}`]: {
              color: "#ffffff"
            }
          }
        }}
      >
        <StyledMenuItem onClick={openFileInNewWindow}>
          <img
            src={newtabSVG}
            alt={t("quickAccessBar.openInNewTab")}
            style={imageStyles}
          />
          {t("quickAccessBar.openInNewTab")}
        </StyledMenuItem>
        <StyledMenuItem onClick={openInCommander}>
          <img
            src={openInCommanderSVG}
            alt={t("quickAccessBar.openInCommander")}
            style={imageStyles}
          />
          {t("quickAccessBar.openInCommander")}
        </StyledMenuItem>
        <Divider />
        <StyledMenuItem onClick={openSaveAsDialog}>
          <img
            src={saveAsSVG}
            alt={t("quickAccessBar.saveAs")}
            style={imageStyles}
          />
          {t("quickAccessBar.saveAs")}
        </StyledMenuItem>
        <StyledMenuItem onClick={saveAsPDFInStorage}>
          <img
            src={saveAsPDFInStorageSVG}
            alt={t("quickAccessBar.saveAsPDF")}
            style={imageStyles}
          />
          {t("quickAccessBar.saveAsPDF")}
        </StyledMenuItem>
        <Divider />
        <StyledMenuItem onClick={downloadDWG}>
          <img
            src={downloadDwgSVG}
            alt={t("quickAccessBar.downloadDWG")}
            style={imageStyles}
          />
          {t("quickAccessBar.downloadDWG")}
        </StyledMenuItem>
        <StyledMenuItem onClick={downloadDXF}>
          <img
            src={downloadDxfSVG}
            alt={t("quickAccessBar.downloadDXF")}
            style={imageStyles}
          />
          {t("quickAccessBar.downloadDXF")}
        </StyledMenuItem>
        <StyledMenuItem onClick={downloadPDF}>
          <img
            src={downloadPdfSVG}
            alt={t("quickAccessBar.downloadPDF")}
            style={imageStyles}
          />
          {t("quickAccessBar.downloadPDF")}
        </StyledMenuItem>
        <StyledMenuItem onClick={download}>
          <img
            src={downloadDwgSVG}
            alt={t("quickAccessBar.download")}
            style={imageStyles}
          />
          {t("quickAccessBar.download")}
        </StyledMenuItem>
        <Divider />
        <StyledMenuItem onClick={exportFile}>
          <img
            src={exportSVG}
            alt={t("quickAccessBar.export")}
            style={imageStyles}
          />
          {t("quickAccessBar.export")}
        </StyledMenuItem>
      </Menu>
    </>
  );
}
