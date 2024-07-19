import React, { useEffect, useState, useCallback } from "react";
import { useIntl, FormattedMessage } from "react-intl";
import { List, Map } from "immutable";
import { observer } from "mobx-react-lite";
import Box from "@mui/material/Box";

import ModalActions from "../../../../actions/ModalActions";
import SmartTable from "../../../SmartTable/SmartTable";
import FilesListActions from "../../../../actions/FilesListActions";
import templatesStore, {
  TemplatesStore
} from "../../../../stores/resources/templates/TempatesStore";
import puclicTemplatesStore, {
  PublicTemplatesStore
} from "../../../../stores/resources/templates/PublicTemplatesStore";
import { ResourceTypes } from "../ResourcesContent";
import templateName from "../../../SmartTable/tables/templates/Name";
import ResourcesBreadcrumbs from "../ResourcesBreadcrumbs";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";
import ResourcesToolbar from "../ResourcesToolbar";
import ResourceFile from "../../../../stores/resources/ResourceFile";
import { OWNER_TYPES } from "../../../../stores/resources/BaseResourcesStore";

const columns = List([{ dataKey: "name", label: "name" }]);
const presentation = Map({
  name: templateName
});

export const CUSTOM_TEMPLATES = "custom";
export const PUBLIC_TEMPLATES = "public";

type Props = {
  type: ResourceTypes;
  libId?: string;
  query?: string;
};

function Templates({ type, libId, query }: Props) {
  const intl = useIntl();
  const [currentStorage, setCurrentStorage] = useState<
    TemplatesStore | PublicTemplatesStore | undefined
  >(TemplatesStore.activeStorage);

  const [staticPath, setStaticPath] = useState("");

  useEffect(() => {
    currentStorage?.loadFolderInfo(libId).catch(() => {
      SnackbarUtils.alertError("Failed to load templates from the server");
    });
  }, [currentStorage, libId]);

  useEffect(() => {
    switch (type) {
      case ResourceTypes.CUSTOM_TEMPLATES_TYPE: {
        setStaticPath("/resources/templates/my");
        setCurrentStorage(templatesStore);

        TemplatesStore.activeStorage = templatesStore;
        break;
      }
      case ResourceTypes.PUBLIC_TEMPLATES_TYPE: {
        setStaticPath("/resources/templates/public");
        setCurrentStorage(puclicTemplatesStore);

        TemplatesStore.activeStorage = puclicTemplatesStore;
        break;
      }
      default: {
        break;
      }
    }
  }, [type]);

  const handleRename = useCallback((id: string) => {
    FilesListActions.setEntityRenameMode(id);
  }, []);

  const dropHandler = useCallback(
    (event: React.DragEvent<HTMLInputElement>) => {
      event.preventDefault();
      event.stopPropagation();

      const { items } = event.dataTransfer;

      if (!currentStorage) return;

      let areAnyFoldersInUpload = false;
      const templates = Array.from(items)
        .filter((item: DataTransferItem) => {
          if (item.webkitGetAsEntry && item.webkitGetAsEntry()?.isDirectory) {
            areAnyFoldersInUpload = true;
            return false;
          }
          return true;
        })
        .map((item: DataTransferItem) => item.getAsFile());

      const promises = currentStorage.loadTemplates(templates as File[]);

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
              templates.length === 1
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
    [currentStorage]
  );

  const uploadClickHandler = useCallback(() => {
    let templateType = CUSTOM_TEMPLATES;
    const { pathname } = location;
    if (pathname.includes("resources/templates/public")) {
      templateType = PUBLIC_TEMPLATES;
    }
    ModalActions.uploadTemplate(templateType);
  }, []);

  const handleFolderCreate = useCallback(() => {
    let ownerType = null;

    if (type === ResourceTypes.CUSTOM_TEMPLATES_TYPE) {
      ownerType = OWNER_TYPES.OWNED;
    } else if (type === ResourceTypes.PUBLIC_TEMPLATES_TYPE) {
      ownerType = OWNER_TYPES.PUBLIC;
    }

    ModalActions.createResourceFolder(currentStorage, ownerType);
  }, [currentStorage]);

  if (!currentStorage) return null;

  return (
    <Box
      sx={{
        "& .ReactVirtualized__Table__headerRow": {
          paddingLeft: theme => theme.spacing(3),
          "& .ReactVirtualized__Table__headerColumn": {
            fontSize: theme => theme.typography.pxToRem(11)
          }
        },
        "& .ReactVirtualized__Table__sortableHeaderIcon": {
          width: "22px",
          height: "22px",
          verticalAlign: "middle"
        },
        "& .ReactVirtualized__Table__Grid .noDataRow": {
          color: theme => theme.palette.JANGO
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
          storage={currentStorage}
          dropHandler={dropHandler}
          folderCreateHandler={handleFolderCreate}
          uploadFileHandler={() => null}
          uploadButtonClickHandler={uploadClickHandler}
          createFolderMessage="createFolder"
          uploadFileMessage="uploadTemplate"
        />
      </Box>
      <ResourcesBreadcrumbs
        staticPatch={staticPath}
        currentFolder={currentStorage?.currentFolder}
      />
      <SmartTable
        noDataCaption={intl.formatMessage({ id: "noFilesInCurrentFolder" })}
        data={currentStorage?.filesAndFolders}
        presentation={presentation}
        columns={columns}
        handleRename={handleRename}
        tableType="templates"
        isLoading={currentStorage?.fetchResourcesInProcess}
      />
    </Box>
  );
}

export default observer(Templates);
