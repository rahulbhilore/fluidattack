import React, { useEffect, useCallback } from "react";
import { FormattedMessage } from "react-intl";
import Immutable, { List } from "immutable";
import { observer } from "mobx-react-lite";
import Box from "@mui/material/Box/Box";
import SmartTable from "../../../SmartTable/SmartTable";
import blocksStore from "../../../../stores/resources/blocks/BlocksStore";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";

import { OWNER_TYPES } from "../../../../stores/resources/BaseResourcesStore";
import ModalActions from "../../../../actions/ModalActions";
import ResourceFile from "../../../../stores/resources/ResourceFile";
import NameColumn from "../../../SmartTable/tables/blocks/Name";
import PermissionsColumn from "../../../SmartTable/tables/blocks/Permissions";
import OwnerColumn from "../../../SmartTable/tables/blocks/Owner";

import ResourcesBreadcrumbs from "../ResourcesBreadcrumbs";
import ResourcesToolbar from "../ResourcesToolbar";

const widths = { name: 0.3, permissions: 0.3, description: 0.2, owner: 0.2 };
const columns = List(
  [
    { dataKey: "name", label: "name", width: widths.name },
    // share
    {
      dataKey: "permissions",
      label: "access",
      width: widths.permissions
    },
    // owner
    { dataKey: "ownerName", label: "owner", width: widths.owner }
  ].filter(v => v !== null)
);

const presentation = Immutable.Map({
  name: NameColumn,
  permissions: PermissionsColumn,
  ownerName: OwnerColumn
});

type Props = {
  libId?: string;
  query?: string;
};

function Blocks({ libId, query }: Props) {
  useEffect(() => {
    blocksStore.loadFolderInfo(libId).catch(() => {
      SnackbarUtils.alertError("Failed to load blocks from the server");
    });
  }, [libId, query]);

  const handleNewLibraryCreation = useCallback(() => {
    ModalActions.createResourceFolder(blocksStore, OWNER_TYPES.OWNED);
  }, []);

  const uploadHandler = useCallback(
    (event: React.FormEvent<HTMLInputElement>) => {
      event.preventDefault();
      event.stopPropagation();

      const { files } = event.currentTarget;

      if (!files) return;

      const promise = blocksStore.loadBlocks(files);

      Promise.all(promise)
        .then(() => {
          SnackbarUtils.alertOk({
            id: "successfulUploadSingle",
            type: "block"
          });
        })
        .catch(() => {
          SnackbarUtils.alertError({
            id: "failedUploadSingle",
            type: "block"
          });
        });
    },
    []
  );

  const uploadClickHandler = useCallback(
    (event: React.SyntheticEvent, input: HTMLInputElement) => {
      event.preventDefault();
      event.stopPropagation();
      input?.click();
    },
    []
  );

  const dropHandler = useCallback(
    (event: React.DragEvent<HTMLInputElement>) => {
      event.preventDefault();
      event.stopPropagation();

      const { items } = event.dataTransfer;

      let areAnyFoldersInUpload = false;
      const blocks = Array.from(items)
        .filter((item: DataTransferItem) => {
          if (item.webkitGetAsEntry && item.webkitGetAsEntry()?.isDirectory) {
            areAnyFoldersInUpload = true;
            return false;
          }
          return true;
        })
        .map((item: DataTransferItem) => item.getAsFile());

      const promises = blocksStore.loadBlocks(blocks as File[]);

      type Messages = {
        id: string;
        type?: React.ReactNode | string;
        duplicates?: string;
      };

      const finalMessages: Messages[] = [];
      let messageType = "success";

      if (areAnyFoldersInUpload) {
        messageType = "warning";
        SnackbarUtils.alertWarning({ id: "folderUploadIsntSupported" });
      }

      Promise.allSettled(promises).then(result => {
        const successed = result.filter(
          item => item.status === "fulfilled"
        ) as PromiseFulfilledResult<ResourceFile>[];
        const failed = result.filter(
          item => item.status === "rejected"
        ) as PromiseRejectedResult[];

        if (!failed.length || successed.length) {
          const messageEntityType =
            successed.length > 1 ? "templates" : "template";

          finalMessages.push({
            id:
              blocks.length === 1
                ? "successfulUploadSingle"
                : "successfulUploadMultiple",
            type: <FormattedMessage id={messageEntityType} />
          });
        }

        if (failed.length) {
          const duplicated = failed.filter(
            item => item.reason.type === "FILE_NAME_DUPLICATED"
          );
          const duplicateNames = duplicated.map(({ reason }) => reason.value);

          if (duplicated.length) {
            messageType = "warning";

            finalMessages.push({
              id: "duplicateNameUpload",
              duplicates: duplicateNames.join("\r\n")
            });
          }
        }

        switch (messageType) {
          case "success":
            SnackbarUtils.alertOk(finalMessages);
            break;
          case "warning":
            SnackbarUtils.alertWarning(finalMessages);
            break;
          default:
            break;
        }
      });
    },
    []
  );

  return (
    <Box
      sx={{
        "& .ReactVirtualized__Table__headerColumn": {
          fontSize: "12px"
        },
        "& .ReactVirtualized__Table__headerColumn:first-of-type": {
          marginLeft: "38px"
        }
      }}
    >
      <Box
        sx={{
          mx: "28px",
          my: 2,
          borderBottom: theme => `1px solid ${theme.palette.BORDER_COLOR}`
        }}
      >
        <ResourcesToolbar
          storage={blocksStore}
          dropHandler={dropHandler}
          folderCreateHandler={handleNewLibraryCreation}
          uploadFileHandler={uploadHandler}
          uploadButtonClickHandler={uploadClickHandler}
          createFolderMessage="createFolder"
          uploadFileMessage="uploadFont"
        />
      </Box>
      <ResourcesBreadcrumbs
        staticPatch="/resources/blocks"
        currentFolder={blocksStore?.currentFolder}
      />
      <SmartTable
        data={blocksStore?.filesAndFolders}
        columns={columns}
        presentation={presentation}
        tableType="blocks"
        rowHeight={80}
      />
    </Box>
  );
}

export default observer(Blocks);
