import React from "react";
import MobileName from "./MobileName";
import { FileEntity } from "../../../ContextMenu/Files";
import LegacyObjectName from "./LegacyObjectName";

type PropType = {
  _id: string;
  deleted?: boolean;
  deshared?: boolean;
  folderId?: string;
  isMobile: boolean;
  name: string;
  showDrawMenu?: boolean;
  viewFlag?: boolean;
  currentFile: FileEntity;
  isRibbonMode?: boolean;
  isOwner?: boolean;
  viewOnly?: boolean;
};

export default function FileCaption({
  _id,
  deleted = false,
  deshared = false,
  folderId = "",
  isMobile,
  name,
  showDrawMenu = false,
  viewFlag = false,
  currentFile,
  isRibbonMode = false,
  viewOnly = false,
  isOwner = false
}: PropType) {
  if (isMobile && isRibbonMode) return null;
  if (isMobile)
    return (
      <MobileName
        deleted={deleted}
        deshared={deshared}
        name={name}
        showDrawMenu={showDrawMenu}
      />
    );

  const isRenameAvailable =
    !deleted && !deshared && (isOwner === true || viewOnly === false);
  return (
    <LegacyObjectName
      deleted={deleted}
      deshared={deshared}
      isRenameAvailable={isRenameAvailable}
      isViewOnly={viewFlag}
      name={name}
      objectId={_id}
      parentId={folderId}
    />
  );
}
