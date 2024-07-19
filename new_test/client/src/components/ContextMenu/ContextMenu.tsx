/* eslint-disable react/no-unused-state */
import React, { useCallback, useEffect } from "react";
import _ from "underscore";
import ContextMenuStore from "../../stores/ContextMenuStore";
import UserInfoStore from "../../stores/UserInfoStore";
import KudoMenu from "./KudoMenu";
import Trash, { getRenderFlags as getTrashFlags } from "./Trash";
import Users, { UserEntity, getRenderFlags as getUsersFlags } from "./Users";
import Templates, {
  TemplateEntity,
  getRenderFlags as getTemplatesFlags
} from "./Templates";
import OldTemplates, {
  getRenderFlags as getOldTemplatesFlags
} from "./OldTemplates";
import RecentFile, {
  RecentFileEntity,
  getRenderFlags as getRecentFlags
} from "./RecentFile";
import Fonts, { FontEntity, getRenderFlags as getFontsFlags } from "./Fonts";
import OldFonts, { getRenderFlags as getOldFontsFlags } from "./OldFonts";
import Versions, {
  VersionEntity,
  getRenderFlags as getVersionFlags
} from "./Versions";
import Files, { FileEntity, getRenderFlags as getFilesFlags } from "./Files";
import Blocks, {
  BlockEntity,
  getRenderFlags as getBlocksFlags
} from "./Blocks";
import OldBlocks, { getRenderFlags as getOldBlocksFlags } from "./OldBlocks";
import FilesListStore from "../../stores/FilesListStore";

type PositionState = {
  isVisible: boolean;
  top: number;
  left: number;
};

export type BasicRenderFlags = {
  entities: Array<Record<string, unknown>>;
  type: string;
  isNeedToRenderMenu: boolean;
};

export type TrashRenderFlags = BasicRenderFlags & {
  type: "trash";
  storage: string;
};

export type RenderFlags = TrashRenderFlags | BasicRenderFlags;

export type InfoProviderFunc = (id: string) => Record<string, unknown> | null;

export type RenderFlagsParams = {
  ids: Array<string>;
  infoProvider: InfoProviderFunc;
};

type DataStateType = {
  objectId: string | null;
  isIsolatedObject: boolean;
  ids: string[];
  infoProvider: InfoProviderFunc;
  customObjectInfo: Record<string, unknown>;
  type: string;
  tableId: string | null;
};

/**
 * @class ContextMenu
 * @description Context menu component. It should be unique!
 */
export default function ContextMenu() {
  const [positionState, setPositionState] = React.useState<PositionState>({
    isVisible: false,
    top: 0,
    left: 0
  });

  const [dataState, setDataState] = React.useState<DataStateType>({
    objectId: null,
    isIsolatedObject: false,
    ids: [],
    infoProvider: () => null,
    customObjectInfo: {},
    type: "",
    tableId: null
  });

  /**
   * @method
   * @private
   * @description Recalculates context menu position regarding
   * initial click position according to the window size
   */
  const recalculatePosition = useCallback(() => {
    const contextMenus = document.getElementsByClassName(
      "contextMenu"
    ) as HTMLCollectionOf<HTMLDivElement>;
    if (contextMenus && contextMenus.length > 0) {
      const contextMenu = contextMenus[0];
      const height = contextMenu.clientHeight;
      const width = contextMenu.clientWidth;
      const offset = {
        top: contextMenu.offsetTop,
        left: contextMenu.offsetLeft
      };
      const docheight = window.innerHeight;
      const docwidth = window.innerWidth;
      const newPosition = {
        top: positionState.top,
        left: positionState.left
      };
      if (height && width) {
        if (height + offset.top > docheight - 45) {
          newPosition.top = docheight - height - 48;
        }
        if (width + offset.left > docwidth) {
          newPosition.left = docwidth - width - 3;
        }
        if (offset.top < 3) {
          newPosition.top = 3;
        }
        if (offset.left < 3) {
          newPosition.left = 3;
        }
        if (
          newPosition.top !== positionState.top ||
          newPosition.left !== positionState.left
        ) {
          setPositionState(prev => ({ ...prev, ...newPosition }));
        }
      }
    }
  }, [positionState]);

  const onChange = useCallback(() => {
    const newInfo = ContextMenuStore.getCurrentInfo();
    setPositionState({
      isVisible: newInfo.isVisible,
      top: newInfo.Y,
      left: newInfo.X
    });
    setDataState({
      objectId: newInfo.objectId,
      isIsolatedObject: newInfo.isIsolatedObject === true,
      customObjectInfo: newInfo.customObjectInfo,
      ids: newInfo.ids,
      infoProvider: newInfo.infoProvider,
      type: newInfo.type,
      tableId: newInfo.tableId
    });

    const tables = document.getElementsByTagName(
      "table"
    ) as HTMLCollectionOf<HTMLTableElement>;
    // set focus to/from table to properly handle shortcuts
    if (newInfo.isVisible === true) {
      Array.from(tables).forEach(table => table.blur());
      if (newInfo.selectedRow >= 0) {
        const contextItems = Array.from(
          document.getElementsByClassName(
            "contextItem"
          ) as HTMLCollectionOf<HTMLDivElement>
        );
        for (let i = 0; i < contextItems.length; i += 1) {
          if (i === newInfo.selectedRow) {
            contextItems[i].classList.add("active");
          } else {
            contextItems[i].classList.remove("active");
          }
        }
      }
    } else {
      tables[0].focus();
    }
  }, []);

  useEffect(() => {
    recalculatePosition();
    ContextMenuStore.addChangeListener(onChange);
    return () => {
      ContextMenuStore.removeChangeListener(onChange);
    };
  }, []);

  const getRenderFlags = (): RenderFlags => {
    // flags number can differ in some cases, so temp variable is created
    const flags: RenderFlags = {
      entities: [],
      type: "",
      isNeedToRenderMenu: false
    };
    const { type, ...restData } = dataState;

    if (type !== "") {
      switch (type) {
        case "blocks":
          return getBlocksFlags(restData);
        case "versions":
          return getVersionFlags({
            ids: restData.ids,
            infoProvider: restData.infoProvider,
            customObjectInfo: restData.customObjectInfo as
              | VersionEntity
              | undefined
          });
        case "fonts":
          return getFontsFlags({
            ids: restData.ids,
            infoProvider: restData.infoProvider,
            customObjectInfo: restData.customObjectInfo as
              | FontEntity
              | undefined
          });
        case "files":
          if (FilesListStore.getCurrentState() !== "trash")
            return getFilesFlags(restData);
          return getTrashFlags({
            ids: restData.ids,
            infoProvider: restData.infoProvider
          });
        case "search":
          return getFilesFlags(restData);
        case "recent":
          return getRecentFlags(restData);
        case "users":
          return getUsersFlags(restData);
        case "templates":
          return getTemplatesFlags(restData);
        case "oldTemplates":
          return getOldTemplatesFlags(restData);
        case "oldFonts":
          // @ts-ignore
          return getOldFontsFlags(restData);
        case "oldBlocks": {
          return getOldBlocksFlags(restData);
        }
        default:
          break;
      }
    }
    return flags;
  };

  let optionsList: Array<React.ReactNode> | null = [];
  if (positionState.isVisible === true) {
    const flags = getRenderFlags();
    if (!flags.isNeedToRenderMenu) optionsList = null;
    else {
      switch (flags.type) {
        case "trash": {
          const isEraseAvailable = UserInfoStore.isFeatureAllowedByStorage(
            (flags as TrashRenderFlags).storage,
            "trash",
            "erase"
          );
          const isRestoreAvailable = UserInfoStore.isFeatureAllowedByStorage(
            (flags as TrashRenderFlags).storage,
            "trash",
            "restore"
          );
          if (!isEraseAvailable && !isRestoreAvailable) {
            optionsList = null;
          } else {
            optionsList = Trash({
              entities: flags.entities,
              isEraseAvailable,
              isRestoreAvailable
            });
          }
          break;
        }
        case "files":
          optionsList = Files({
            entities: flags.entities as Array<FileEntity>,
            tableId: dataState.tableId || ""
          });
          break;
        case "users":
          optionsList = Users({
            entities: flags.entities as Array<UserEntity>
          });
          break;
        case "templates":
          optionsList = Templates({
            entities: flags.entities as Array<TemplateEntity>
          });
          break;
        case "recent":
          optionsList = RecentFile({
            entities: flags.entities as Array<RecentFileEntity>
          });
          break;
        case "versions":
          optionsList = Versions({
            entities: flags.entities as Array<VersionEntity>
          });
          break;
        case "fonts":
          optionsList = Fonts({
            entities: flags.entities as Array<FontEntity>
          });
          break;
        case "blocks":
          optionsList = Blocks({
            entities: flags.entities as Array<BlockEntity>
          });
          break;
        case "oldTemplates":
          optionsList = OldTemplates({
            entities: flags.entities as Array<TemplateEntity>
          });
          break;
        case "oldFonts":
          optionsList = OldFonts({
            entities: flags.entities as Array<FontEntity>
          });
          break;
        case "oldBlocks":
          optionsList = OldBlocks({
            // @ts-ignore
            entities: flags.entities as Array<BlockEntity>
          });
          break;
        default:
          break;
      }
    }
  }

  if (!optionsList || optionsList.length === 0) return null;

  return (
    <KudoMenu
      isVisible={positionState.isVisible}
      top={positionState.top}
      left={positionState.left}
    >
      {optionsList}
    </KudoMenu>
  );
}
