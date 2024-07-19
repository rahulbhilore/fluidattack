import React, { useEffect, useState, useCallback } from "react";
import { observer } from "mobx-react-lite";
import { FormattedMessage } from "react-intl";
import { List, Map } from "immutable";
import Box from "@mui/material/Box";
import ModalActions from "../../../../actions/ModalActions";
import fontsStore, {
  FontsStore
} from "../../../../stores/resources/fonts/FontsStore";
import companyFontsStore, {
  CompanyFontsStore
} from "../../../../stores/resources/fonts/CompanyFontsStore";
import SmartTable from "../../../SmartTable/SmartTable";
import ResourcesToolbar from "../ResourcesToolbar";
import fontNameTable from "../../../SmartTable/tables/fonts/FontName";
import fontFamilyTable from "../../../SmartTable/tables/fonts/FontFamily";
import fontSizeTable from "../../../SmartTable/tables/fonts/FontSize";
import { ResourceTypes } from "../ResourcesContent";
import ResourcesBreadcrumbs from "../ResourcesBreadcrumbs";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";
import { OWNER_TYPES } from "../../../../stores/resources/BaseResourcesStore";
import FontResourceFile from "../../../../stores/resources/fonts/FontResourceFile";

const columns = List([
  { dataKey: "name", label: "fontName", width: 0.65 },
  { dataKey: "type", label: "fontFamily", width: 0.25 },
  { dataKey: "fileSize", label: "size", width: 0.1 }
]);

const presentation = Map({
  name: fontNameTable,
  type: fontFamilyTable,
  fileSize: fontSizeTable
});

export const CUSTOM_FONTS = "custom";
export const COMPANY_FONTS = "company";

type Props = {
  type: ResourceTypes;
  libId?: string;
  query?: string;
};

function Fonts({ type, libId, query }: Props) {
  const [currentStorage, setCurrentStorage] = useState<
    FontsStore | CompanyFontsStore | undefined
  >(FontsStore.activeStorage);

  const [staticPath, setStaticPath] = useState("");

  useEffect(() => {
    currentStorage?.loadFolderInfo(libId).catch(() => {
      SnackbarUtils.alertError("Failed to load blocks from the server");
    });
  }, [currentStorage, libId]);

  useEffect(() => {
    switch (type) {
      case ResourceTypes.CUSTOM_FONTS_TYPE: {
        setCurrentStorage(fontsStore);
        setStaticPath("/resources/fonts/my");
        FontsStore.activeStorage = fontsStore;
        break;
      }
      case ResourceTypes.COMPANY_FONTS_TYPE: {
        setCurrentStorage(companyFontsStore);
        setStaticPath("/resources/fonts/public");
        FontsStore.activeStorage = companyFontsStore;
        break;
      }
      default: {
        break;
      }
    }
  }, [currentStorage, type]);

  const handleFolderCreate = useCallback(() => {
    let ownerType = null;

    if (type === ResourceTypes.CUSTOM_FONTS_TYPE) {
      ownerType = OWNER_TYPES.OWNED;
    } else if (type === ResourceTypes.COMPANY_FONTS_TYPE) {
      ownerType = OWNER_TYPES.ORG;
    }

    ModalActions.createResourceFolder(currentStorage, ownerType);
  }, []);

  const dropHandler = useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      event.stopPropagation();

      const { items } = event.dataTransfer;

      if (!currentStorage) return;

      let areAnyFoldersInUpload = false;
      const fonts = Array.from(items)
        .filter((item: DataTransferItem) => {
          if (item.webkitGetAsEntry && item.webkitGetAsEntry()?.isDirectory) {
            areAnyFoldersInUpload = true;
            return false;
          }
          return true;
        })
        .map((item: DataTransferItem) => item.getAsFile());

      const promises = currentStorage.loadFonts(fonts as File[]);
      type Messages = {
        id: string;
        type?: React.ReactNode | string;
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
        ) as PromiseFulfilledResult<FontResourceFile>[];
        const failed = result.filter(
          item => item.status === "rejected"
        ) as PromiseRejectedResult[];

        if (!failed.length || successed.length) {
          const messageEntityType = successed.length > 1 ? "fonts" : "font";

          finalMessages.push({
            id:
              fonts.length === 1
                ? "successfulUploadSingle"
                : "successfulUploadMultiple",
            type: <FormattedMessage id={messageEntityType} />
          });
        }

        if (failed.length) {
          messageType = "error";
        }

        switch (messageType) {
          case "success":
            SnackbarUtils.alertOk(finalMessages);
            break;
          case "warning":
            SnackbarUtils.alertWarning(finalMessages);
            break;
          case "error":
            SnackbarUtils.alertError(finalMessages);
            break;
          default:
            break;
        }
      });
    },
    [currentStorage]
  );

  const uploadClickHandler = useCallback(
    (event: React.SyntheticEvent, input: HTMLInputElement) => {
      event.preventDefault();
      event.stopPropagation();
      input?.click();
    },
    []
  );

  const uploadHandler = useCallback(
    (event: React.FormEvent<HTMLInputElement>) => {
      event.preventDefault();
      event.stopPropagation();

      if (!currentStorage) return null;

      const { files } = event.currentTarget;

      if (!files) return null;

      const promise = currentStorage.loadFonts(files);

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

      return null;
    },
    [currentStorage]
  );

  if (!currentStorage) return null;

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
          storage={currentStorage}
          dropHandler={dropHandler}
          folderCreateHandler={handleFolderCreate}
          uploadFileHandler={uploadHandler}
          uploadButtonClickHandler={uploadClickHandler}
          createFolderMessage="createFolder"
          uploadFileMessage="uploadFont"
        />
      </Box>
      <ResourcesBreadcrumbs
        staticPatch={staticPath}
        currentFolder={currentStorage?.currentFolder}
      />
      <SmartTable
        data={currentStorage?.filesAndFolders}
        columns={columns}
        presentation={presentation}
        tableType="fonts"
        rowHeight={80}
        isLoading={currentStorage?.fetchResourcesInProcess}
      />
    </Box>
  );
}

export default observer(Fonts);
