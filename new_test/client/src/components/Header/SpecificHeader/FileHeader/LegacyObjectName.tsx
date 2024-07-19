import { Box, Typography } from "@mui/material";
import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import FilesListActions from "../../../../actions/FilesListActions";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListStore from "../../../../stores/FilesListStore";
import UserInfoStore from "../../../../stores/UserInfoStore";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import RenameField from "./RenameField";
import {
  StorageType,
  StorageValues,
  UserStoragesInfo
} from "../../../../types/StorageTypes";

type PropType = {
  deleted?: boolean;
  deshared?: boolean;
  isRenameAvailable?: boolean;
  isViewOnly?: boolean;
  name: string;
  objectId: string;
  parentId?: string;
};

export default function LegacyObjectName({
  deleted = false,
  deshared = false,
  isRenameAvailable = true,
  isViewOnly = false,
  name,
  objectId,
  parentId = ""
}: PropType) {
  const [path, setPath] = useState<string[]>([]);
  const [isRename, setRename] = useState(false);
  const [showTooltip, setTooltip] = useState(false);

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

  const startRename = () => {
    if (isRenameAvailable) setRename(true);
  };
  const stopRename = () => {
    setRename(false);
  };
  const openTooltip = () => {
    setTooltip(true);
  };
  const closeTooltip = () => {
    setTooltip(false);
  };
  return (
    <SmartTooltip
      placement="bottom"
      forcedOpen={showTooltip}
      title={path.length > 0 ? `${path.join("\\")}\\${name}` : name}
    >
      <Box
        sx={{ display: "inline" }}
        onMouseEnter={openTooltip}
        onMouseLeave={closeTooltip}
        onClick={closeTooltip}
      >
        {isRename ? (
          <RenameField
            id={objectId}
            initialValue={name}
            onFinish={stopRename}
          />
        ) : (
          <Typography
            data-component="file_name"
            data-text={name}
            variant="body2"
            sx={{
              display: "inline-block",
              height: "36px",
              lineHeight: "36px",
              padding: "0 7px",
              border: "1px solid transparent",
              borderRadius: "2px",
              cursor: "text",
              color: theme => theme.palette.WHITE_HEADER_NAME,
              "&:hover,&:focus,&:active": {
                borderColor: theme => theme.palette.JANGO,
                color: theme => theme.palette.WHITE_HEADER_NAME
              }
            }}
            onClick={startRename}
            onKeyDown={startRename}
          >
            {`${MainFunctions.shrinkString(name, 30)} `}
            {/* Irreplaceable - can be updated by DrawingLoader due to different reasons */}
            {deshared && <FormattedMessage id="Deshared" />}
            {deleted && <FormattedMessage id="Deleted" />}
            {!deleted && !deshared && isViewOnly && (
              <FormattedMessage id="ViewOnly" />
            )}
          </Typography>
        )}
      </Box>
    </SmartTooltip>
  );
}
