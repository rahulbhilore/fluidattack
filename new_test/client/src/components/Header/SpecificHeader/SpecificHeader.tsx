import React, { SyntheticEvent, useEffect, useState } from "react";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListStore, {
  CURRENT_FILE_INFO_UPDATED,
  DRAWING_RELOAD
} from "../../../stores/FilesListStore";
import Storage from "../../../utils/Storage";
import FileHeader from "./FileHeader";
import FilePublicAccess from "./FilePublicAccess";
import OtherCasesHeader from "./OtherCasesHeader";
import UserNotLogged from "./UserNotLogged";

type PropType = {
  clearFileDeleted: () => void;
  fileDeleted?: boolean;
  isMobile: boolean;
  notificationBadge: number;
  onCommentButtonClick: (e?: SyntheticEvent) => void;
};

export default function SpecificHeader({
  clearFileDeleted,
  fileDeleted = false,
  isMobile,
  notificationBadge,
  onCommentButtonClick
}: PropType) {
  const [currentFile, setCurrentFile] = useState(
    FilesListStore.getCurrentFile()
  );
  const updateFileInfo = () => {
    const freshCurrentFile = FilesListStore.getCurrentFile();
    const { _id: freshId } = freshCurrentFile;
    const { _id: currentId } = currentFile;

    if (freshId !== currentId) clearFileDeleted();

    setCurrentFile(FilesListStore.getCurrentFile());
  };
  useEffect(() => {
    FilesListStore.addChangeListener(updateFileInfo);
    FilesListStore.addEventListener(CURRENT_FILE_INFO_UPDATED, updateFileInfo);
    FilesListStore.addEventListener(DRAWING_RELOAD, updateFileInfo);
    return () => {
      FilesListStore.removeChangeListener(updateFileInfo);
      FilesListStore.removeEventListener(
        CURRENT_FILE_INFO_UPDATED,
        updateFileInfo
      );
      FilesListStore.removeEventListener(DRAWING_RELOAD, updateFileInfo);
    };
  }, []);

  const isLoggedIn = !!Storage.store("sessionId");
  const currentPage = MainFunctions.detectPageType();
  const isPublicAccess = currentPage === "file" && !isLoggedIn;
  const isFileInfoLoaded = !!(currentFile.name && currentFile._id);

  if (currentPage === "file" && isFileInfoLoaded && isPublicAccess)
    return <FilePublicAccess currentFile={currentFile} isMobile={isMobile} />;

  if (currentPage === "file" && isFileInfoLoaded)
    return (
      <FileHeader
        fileDeleted={fileDeleted}
        notificationBadge={notificationBadge}
        onCommentButtonClick={onCommentButtonClick}
        currentFile={currentFile}
        isMobile={isMobile}
      />
    );

  if (currentPage === "index" || currentPage === "signup")
    return <UserNotLogged isMobile={isMobile} />;

  return <OtherCasesHeader isMobile={isMobile} />;
}
