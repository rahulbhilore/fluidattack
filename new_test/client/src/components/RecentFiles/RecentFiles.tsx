import { styled, useMediaQuery, useTheme } from "@mui/material";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { FormattedMessage } from "react-intl";
import FilesListActions from "../../actions/FilesListActions";
import SmartTableActions from "../../actions/SmartTableActions";
import MainFunctions from "../../libraries/MainFunctions";
import FilesListStore from "../../stores/FilesListStore";
import UserInfoStore, {
  RECENT_FILES_SWITCH_UPDATE,
  RECENT_FILES_UPDATE
} from "../../stores/UserInfoStore";
import FileBlock, { THUMBNAIL_WIDTH } from "./FileBlock/FileBlock";

const RECENT_FILES_LOAD = "RECENT_FILES_LOAD";

const StyledDivContainer = styled("div")(({ theme }) => ({
  padding: "0 30px",
  backgroundColor: theme.palette.LIGHT,
  marginLeft: 0,
  width: `calc(100vw - ${theme.kudoStyles.SIDEBAR_WIDTH})`,
  "@media (max-width: 960px)": {
    width: "100vw"
  },
  "@media (max-width: 767px)": {
    padding: "0 10px"
  }
}));

const StyledCaption = styled("h3")(({ theme }) => ({
  display: "block",
  textAlign: "left",
  margin: "0 0 10px 0",
  paddingTop: "10px",
  color: theme.palette.OBI,
  fontWeight: "bold",
  fontSize: "12px"
}));

const StyledRecentFilesContainer = styled("div")(() => ({
  display: "inline-block",
  whiteSpace: "nowrap",
  paddingBottom: "10px"
}));

export type RecentFile = {
  fileId: string;
  filename: string;
  folderId: string;
  storageType: string;
  thumbnail: string;
  timestamp: number;
};

export default function RecentFiles() {
  const [recentFiles, setRecentFiles] = useState<Array<RecentFile>>([]);
  const [itemsBoundaries, setItemsBoundaries] = useState({ start: 0, end: 0 });
  const [
    hasRecentFilesSwitchStateChanged,
    setHasRecentFilesSwitchStateChanged
  ] = useState(UserInfoStore.getUserInfo("isRecentFilesSwitchUpdated"));
  const theme = useTheme();
  const isSmallerThanMd = useMediaQuery(theme.breakpoints.down("md"));
  const isMobile = useMemo(
    () => MainFunctions.isMobileDevice() || isSmallerThanMd,
    [isSmallerThanMd]
  );

  const calculateRecentFilesBlocks = useCallback(() => {
    const containerWidth =
      window.innerWidth - parseInt(theme.kudoStyles.SIDEBAR_WIDTH, 10);
    const recentFileFullWidth =
      THUMBNAIL_WIDTH + (window.innerWidth > 768 ? 30 : 15);
    const neededCount = Math.floor(containerWidth / recentFileFullWidth);

    setItemsBoundaries(prev => ({ ...prev, end: prev.start + neededCount }));
  }, [theme.kudoStyles.SIDEBAR_WIDTH, window.innerWidth]);

  const onRecentFilesLoaded = useCallback(() => {
    setRecentFiles(FilesListStore.getRecentFiles() as Array<RecentFile>);
    calculateRecentFilesBlocks();
  }, [calculateRecentFilesBlocks]);

  const recentFileUpdate = useCallback(() => {
    calculateRecentFilesBlocks();
    SmartTableActions.recalculateDimensions();
  }, [calculateRecentFilesBlocks]);

  const onRecentSwitchStateChanged = useCallback(() => {
    setHasRecentFilesSwitchStateChanged(
      UserInfoStore.getUserInfo("isRecentFilesSwitchUpdated")
    );
  }, []);

  useEffect(() => {
    UserInfoStore.addListener(RECENT_FILES_UPDATE, recentFileUpdate);
    FilesListStore.addListener(RECENT_FILES_LOAD, onRecentFilesLoaded);
    FilesListActions.loadRecentFiles();
    UserInfoStore.addChangeListener(
      RECENT_FILES_SWITCH_UPDATE,
      onRecentSwitchStateChanged
    );
    window.addEventListener("resize", calculateRecentFilesBlocks);
    calculateRecentFilesBlocks();
    return () => {
      UserInfoStore.removeChangeListener(RECENT_FILES_UPDATE, recentFileUpdate);
      FilesListStore.removeListener(RECENT_FILES_LOAD, onRecentFilesLoaded);
      UserInfoStore.removeChangeListener(
        RECENT_FILES_SWITCH_UPDATE,
        onRecentSwitchStateChanged
      );
      window.removeEventListener("resize", calculateRecentFilesBlocks);
    };
  }, []);

  const isUserInfoLoaded =
    UserInfoStore.getUserInfo("isLoggedIn") &&
    UserInfoStore.getUserInfo("isFullInfo");
  const isShowRecent = UserInfoStore.getUserInfo("showRecent");

  if (
    !isShowRecent ||
    recentFiles.length === 0 ||
    isUserInfoLoaded === false ||
    (!hasRecentFilesSwitchStateChanged && isMobile)
  )
    return null;

  return (
    <StyledDivContainer>
      <StyledCaption>
        <FormattedMessage id="recentFiles" />
      </StyledCaption>
      <StyledRecentFilesContainer>
        {recentFiles.map((recentFile, i) => (
          <FileBlock
            key={recentFile.fileId}
            id={recentFile.fileId}
            name={recentFile.filename}
            folderId={recentFile.folderId}
            storage={recentFile.storageType}
            thumbnail={recentFile.thumbnail || ""}
            date={recentFile.timestamp}
            isVisible={i >= itemsBoundaries.start && i < itemsBoundaries.end}
          />
        ))}
      </StyledRecentFilesContainer>
    </StyledDivContainer>
  );
}
