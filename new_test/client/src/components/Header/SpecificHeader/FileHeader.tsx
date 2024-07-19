import { Box, Grid, useTheme } from "@mui/material";
import React, {
  SyntheticEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from "react";
import FilesListStore, {
  CURRENT_FILE_DESHARED
} from "../../../stores/FilesListStore";
import ChangeEditorUrlButton from "./FileHeader/ChangeEditorUrlButton";
import CommentsButton from "./FileHeader/CommentsButton";
import FileCaption from "./FileHeader/FileCaption";
import PermissionsDialogButton from "./FileHeader/PermissionsDialogButton";
import UpgradeSessionButton from "./FileHeader/UpgradeSessionButton";
import VersionControlButton from "./FileHeader/VersionControlButton";
import SaveInfoField from "./SaveInfoField";
import useTranslate from "../../../hooks/useTranslate";
import { FileEntity } from "../../ContextMenu/Files";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import XenonConnectionStore from "../../../stores/XenonConnectionStore";
import UndoSVG from "./FileHeader/images/UndoSVG";
import RedoSVG from "./FileHeader/images/RedoSVG";
import printToPdfSVG from "../../../assets/images/header/print_to_pdf.svg";
import saveSVG from "../../../assets/images/header/Save.svg";
import userInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import BlockLibraryButton from "./FileHeader/BlockLibraryButton";
import conflictingReasons from "../../../constants/appConstants/ConflictingFileReasons";
import OpenInCommander from "./FileHeader/OpenInCommander";
import IconButton from "./IconButton";

type Permissions = {
  canManagePermissions: boolean;
  canManagePublicLink: boolean;
  canViewPermissions: boolean;
  canViewPublicLink: boolean;
};

type PropType = {
  fileDeleted: boolean;
  notificationBadge: number;
  onCommentButtonClick: (e: SyntheticEvent) => void;
  currentFile: FileEntity;
  isMobile: boolean;
};

export default function FileHeader({
  currentFile,
  fileDeleted,
  isMobile,
  notificationBadge,
  onCommentButtonClick
}: PropType) {
  const [fileUnshared, setFileUnshared] = useState(false);
  const theme = useTheme();
  const isDrawMenuNeedToShow = useMemo(
    () =>
      isMobile &&
      window.innerWidth < theme.kudoStyles.THRESHOLD_TO_SHOW_DRAW_MENU,
    [isMobile, theme]
  );
  const [isSmallerThanSm, setSmallerThanSm] = useState(window.innerWidth < 700);
  const [showDrawMenu, setShowDrawMenu] = useState(isDrawMenuNeedToShow);
  const {
    _id,
    folderId,
    viewFlag = true,
    isOwner,
    viewOnly,
    isExport,
    name,
    updateDate,
    changer,
    permissions = {} as Permissions,
    shared
  } = currentFile;
  const { conflictingReason = null } = currentFile;
  const isPermissionsButtonAvailable = useMemo(
    () =>
      Object.keys(permissions).length > 0
        ? permissions.canManagePermissions ||
          permissions.canViewPermissions ||
          permissions.canViewPublicLink ||
          permissions.canManagePublicLink
        : isOwner || !!shared,
    [permissions, shared, isOwner]
  );

  const onWindowResize = () => {
    setShowDrawMenu(isDrawMenuNeedToShow);
    setSmallerThanSm(window.innerWidth < 700);
  };

  const onCurrentFileDeshared = () => setFileUnshared(true);

  useEffect(() => {
    const observer = new ResizeObserver(() => {
      onWindowResize();
    });
    observer.observe(document.body);
    FilesListStore.addEventListener(
      CURRENT_FILE_DESHARED,
      onCurrentFileDeshared
    );
    return () => {
      observer.unobserve(document.body);
      FilesListStore.removeEventListener(
        CURRENT_FILE_DESHARED,
        onCurrentFileDeshared
      );
    };
  }, []);

  const { t } = useTranslate();

  const triggerSave = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "SAVE" });
  }, []);

  const triggerUndo = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "undo" });
  }, []);

  const triggerRedo = useCallback(() => {
    XenonConnectionActions.postMessage({ messageName: "redo" });
  }, []);

  const exportFile = useCallback(() => {
    if (viewOnly && !isExport) return;
    XenonConnectionActions.postMessage({ messageName: "exportPdf" });
  }, [currentFile]);

  const [isUndoEnabled, setUndoEnabled] = useState(false);
  const [isRedoEnabled, setRedoEnabled] = useState(false);

  const onXeMessage = useCallback(() => {
    const incomingMessage = XenonConnectionStore.getCurrentState()
      .lastMessage as {
      messageName?: string;
      message?: string;
    };

    if (
      incomingMessage &&
      incomingMessage.messageName === "updateUndoRedoState" &&
      incomingMessage.message
    ) {
      try {
        const messageContent = JSON.parse(incomingMessage.message) as {
          undoState: "true" | "false";
          redoState: "true" | "false";
        };
        if (
          messageContent.undoState !== undefined &&
          messageContent.redoState !== undefined
        ) {
          setUndoEnabled(`${messageContent.undoState}` === "true");
          setRedoEnabled(`${messageContent.redoState}` === "true");
        }
      } catch (ex) {
        console.error(`Error in onXeMessage: ${ex}`);
      }
    }
  }, []);

  const [isOldUIMode, setOldUIMode] = useState(
    userInfoStore.getUserInfo("preferences")?.useOldUI || false
  );

  const onInfoUpdate = useCallback(() => {
    setOldUIMode(userInfoStore.getUserInfo("preferences")?.useOldUI || false);
  }, []);

  useEffect(() => {
    XenonConnectionStore.addChangeListener(onXeMessage);
    userInfoStore.addChangeListener(INFO_UPDATE, onInfoUpdate);
    return () => {
      XenonConnectionStore.removeChangeListener(onXeMessage);
      userInfoStore.removeChangeListener(INFO_UPDATE, onInfoUpdate);
    };
  }, []);

  return (
    <Grid
      item
      sx={[
        {
          alignSelf: "center",
          alignItems: "center",
          gap: 2,
          display: "flex",
          paddingTop: "0px"
        },
        isMobile && { marginTop: 0 }
      ]}
    >
      <FileCaption
        _id={_id}
        deleted={fileDeleted}
        deshared={fileUnshared}
        folderId={folderId}
        isMobile={isMobile}
        name={name}
        showDrawMenu={showDrawMenu}
        viewFlag={viewFlag}
        currentFile={currentFile}
        isRibbonMode={!isOldUIMode}
        viewOnly={viewOnly}
        isOwner={isOwner}
      />
      {!fileDeleted && !fileUnshared && (!isOldUIMode || !isMobile) && (
        <Box
          sx={{
            display: "flex",
            alignItems: "center",
            gap: isSmallerThanSm && !isOldUIMode && !viewFlag ? "2px" : "16px"
          }}
        >
          {!viewFlag && !isOldUIMode && (
            <IconButton
              dataComponent="save_action"
              icon={saveSVG}
              iconHeight={16}
              iconWidth={16}
              onClick={triggerSave}
              caption={t("quickAccessBar.save")}
            />
          )}
          <CommentsButton
            notificationBadge={notificationBadge}
            onCommentButtonClick={onCommentButtonClick}
          />
          <VersionControlButton
            _id={_id}
            folderId={folderId}
            filename={name}
            viewOnly={viewOnly}
          />
          {isPermissionsButtonAvailable ? (
            <PermissionsDialogButton fileId={_id} name={name} />
          ) : null}
          {!isOldUIMode && (!viewOnly || isExport) && (
            <IconButton
              dataComponent="printToPdf_action"
              icon={printToPdfSVG}
              iconWidth={17}
              iconHeight={17}
              onClick={exportFile}
              isDisabled={viewOnly && !isExport}
              caption={t("quickAccessBar.printToPDF")}
            />
          )}
          {!viewFlag && !isOldUIMode && (
            <>
              <IconButton
                dataComponent="undo_action"
                icon={<UndoSVG enabled={isUndoEnabled} />}
                onClick={triggerUndo}
                isDisabled={!isUndoEnabled}
                caption={t("quickAccessBar.undo")}
              />

              <IconButton
                dataComponent="redo_action"
                icon={<RedoSVG enabled={isRedoEnabled} />}
                onClick={triggerRedo}
                isDisabled={!isRedoEnabled}
                caption={t("quickAccessBar.redo")}
              />
            </>
          )}
          <UpgradeSessionButton
            viewFlag={viewFlag}
            conflictingReason={conflictingReason}
          />
          <ChangeEditorUrlButton />
          {isOldUIMode && (
            <>
              <BlockLibraryButton viewFlag={viewFlag} />
              {conflictingReason !== conflictingReasons.UNSHARED_OR_DELETED && (
                <OpenInCommander id={_id} folderId={folderId} name={name} />
              )}
            </>
          )}
        </Box>
      )}
      {!isMobile && <SaveInfoField timeStamp={updateDate} changer={changer} />}
    </Grid>
  );
}
