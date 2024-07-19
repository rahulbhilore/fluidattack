import EventEmitter from "events";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as BlocksConstants from "../constants/BlocksConstants";
import ProcessActions from "../actions/ProcessActions";
import MainFunctions from "../libraries/MainFunctions";
import Processes from "../constants/appConstants/Processes";
import BlocksActions from "../actions/BlocksActions";
import UserInfoStore from "./UserInfoStore";

export const BLOCKS_UPDATE = "BLOCKS_UPDATE";
export const BLOCKS_REQUIRE_UPDATE = "BLOCKS_REQUIRE_UPDATE";
export const BLOCKS_SORT_REQUIRED = "BLOCKS_SORT_REQUIRED";

export const BLOCK = "block";
export const LIBRARY = "library";

class BlocksStore extends EventEmitter {
  constructor() {
    super();
    this.dispatcherIndex = AppDispatcher.register(this.handleAction);
    this.blocks = [];
    this.userLibraries = [];
    this.orgLibraries = [];
    this.publicLibraries = [];
    this.librariesInfo = [];
  }

  processSucessfullyUploadedBlocks = (libraryId, blocksList) => {
    const infoPromises = blocksList.map(
      blockUploadResponse =>
        new Promise(resolve => {
          const { blockId } = blockUploadResponse.get("response");
          BlocksActions.getBlockInfo(blockId, libraryId).then(info => {
            resolve(info.data);
          });
        })
    );

    Promise.all(infoPromises).then(uploadedBlocksInfo => {
      const oldProcesses = [];
      const uploadedNames = uploadedBlocksInfo.map(({ name }) => name);
      this.blocks = this.blocks.map(blockInfo => {
        if (uploadedNames.includes(blockInfo.name)) {
          oldProcesses.push(blockInfo.id);
          const foundNewBlockInfo = uploadedBlocksInfo.find(
            ({ name }) => name === blockInfo.name
          );
          return { ...foundNewBlockInfo, type: BLOCK };
        }
        return blockInfo;
      });

      if (oldProcesses !== null) {
        oldProcesses.forEach(oldProcessId => {
          ProcessActions.end(oldProcessId);
        });
      }
      const newIds = uploadedBlocksInfo.map(({ id }) => id);
      newIds.forEach(newId => {
        ProcessActions.start(newId, Processes.UPLOAD_COMPLETE, newId);
        setTimeout(() => {
          ProcessActions.end(newId);
        }, 5000);
      });
      this.emit(BLOCKS_UPDATE);
      this.emit(BLOCKS_SORT_REQUIRED);
      setTimeout(() => {
        this.emit(BLOCKS_SORT_REQUIRED);
      }, 5010);
    });
  };

  getLibraryInfo = libraryId => this.librariesInfo[libraryId];

  handleAction = action => {
    if (action.actionType.indexOf(BlocksConstants.constantPrefix) === -1)
      return;

    switch (action.actionType) {
      case BlocksConstants.GET_BLOCK_LIBRARIES_SUCCESS: {
        if (action.ownerType === "USER") {
          this.userLibraries = action.info.results.map(o => ({
            ...o,
            type: LIBRARY
          }));
        } else if (action.ownerType === "ORG") {
          this.orgLibraries = action.info.results.map(o => ({
            ...o,
            type: LIBRARY
          }));
        } else if (action.ownerType === "PUBLIC") {
          this.publicLibraries = action.info.results.map(o => ({
            ...o,
            type: LIBRARY
          }));
        }
        this.blocks = [
          ...this.userLibraries,
          ...this.orgLibraries,
          ...this.publicLibraries
        ];
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.GET_BLOCK_LIBRARY_CONTENT_SUCCESS: {
        this.blocks = action.info.results.map(o => ({ ...o, type: BLOCK }));
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.DELETE_BLOCKS_SUCCESS: {
        this.blocks = this.blocks.filter(
          block => !action.blockIds.includes(block.id)
        );
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.DELETE_LIBRARIES_SUCCESS: {
        this.blocks = this.blocks.filter(
          lib => !action.librariesIds.includes(lib.id)
        );
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.SEARCH_BLOCKS_SUCCESS: {
        const blocks = action.info.results.blocks.map(o => ({
          ...o,
          type: BLOCK
        }));
        const libraries = action.info.results.blockLibraries.map(o => ({
          ...o,
          type: LIBRARY
        }));
        this.blocks = [...libraries, ...blocks];

        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.CREATE_BLOCK_LIBRARY: {
        const blockId = MainFunctions.guid();
        this.blocks.push({
          id: blockId,
          name: action.name,
          description: action.description,
          ownerType: action.ownerType,
          ownerId: action.ownerId,
          shares: [],
          type: LIBRARY
        });
        ProcessActions.start(blockId, Processes.CREATING, blockId);
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.CREATE_BLOCK_LIBRARY_SUCCESS: {
        const { libId } = action.info;

        BlocksActions.getBlockLibraryInfo(libId).then(info => {
          const { id, name } = info.data;
          // find block library by it's name
          let oldProcessId = null;
          this.blocks = this.blocks.map(blockInfo => {
            if (blockInfo.name === name) {
              oldProcessId = blockInfo.id;
              return { ...info.data, type: LIBRARY };
            }
            return blockInfo;
          });
          if (oldProcessId !== null) {
            ProcessActions.end(oldProcessId);
          }
          ProcessActions.start(id, Processes.CREATE_COMPLETED, id);
          this.emit(BLOCKS_UPDATE);
          this.emit(BLOCKS_SORT_REQUIRED);
          setTimeout(() => {
            ProcessActions.end(id);
            setTimeout(() => {
              this.emit(BLOCKS_SORT_REQUIRED);
            }, 0);
          }, 5000);
        });
        break;
      }
      case BlocksConstants.CREATE_BLOCK_LIBRARY_FAIL: {
        const { name } = action;
        this.blocks = this.blocks.filter(blockInfo => {
          if (name === blockInfo.name) {
            const blockId = blockInfo.id;
            // end event just in case
            ProcessActions.end(blockId);

            // there's a chance this is duplicate, so we need to preserve original file
            if (blockId.startsWith("CS_")) return false;
            return true;
          }
          return true;
        });
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.UPLOAD_BLOCK: {
        const blockId = MainFunctions.guid();
        this.blocks.push({
          id: blockId,
          name: action.name,
          description: action.description,
          ownerType: action.ownerType,
          ownerId: action.ownerId,
          shares: [],
          type: BLOCK
        });
        ProcessActions.start(blockId, Processes.UPLOADING, blockId);
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.UPLOAD_BLOCKS: {
        const currentUserId = UserInfoStore.getUserInfo("id");
        action.names.forEach(blockName => {
          const blockId = MainFunctions.guid();
          this.blocks.push({
            id: blockId,
            name: blockName,
            description: "",
            ownerType: "USER",
            ownerId: currentUserId,
            shares: [],
            type: BLOCK
          });
          ProcessActions.start(blockId, Processes.UPLOADING, blockId);
        });

        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.UPLOAD_BLOCK_SUCCESS: {
        const { blockId } = action.info;
        const { libraryId } = action;

        BlocksActions.getBlockInfo(blockId, libraryId).then(info => {
          const { id, name } = info.data;
          // find block library by it's name
          let oldProcessId = null;
          this.blocks = this.blocks.map(blockInfo => {
            if (blockInfo.name === name) {
              oldProcessId = blockInfo.id;
              return { ...info.data, type: BLOCK };
            }
            return blockInfo;
          });
          if (oldProcessId !== null) {
            ProcessActions.end(oldProcessId);
          }
          ProcessActions.start(id, Processes.UPLOAD_COMPLETE, id);
          this.emit(BLOCKS_UPDATE);
          this.emit(BLOCKS_SORT_REQUIRED);
          setTimeout(() => {
            ProcessActions.end(id);
            setTimeout(() => {
              this.emit(BLOCKS_SORT_REQUIRED);
            }, 0);
          }, 5000);
        });
        break;
      }
      case BlocksConstants.UPLOAD_BLOCKS_SUCCESS: {
        this.processSucessfullyUploadedBlocks(action.libraryId, action.info);
        break;
      }
      case BlocksConstants.UPLOAD_BLOCKS_FAIL: {
        let failedNames = [];
        // if names are included - validation error (duplicate, incorrect format)
        if (Object.prototype.hasOwnProperty.call(action, "names")) {
          const { names } = action;
          failedNames = names;
        } else {
          // during upload process
          // fails if any fails
          const { info } = action;
          const failedRequests = info.filter(requestInfo =>
            requestInfo.has("error")
          );
          failedNames = failedRequests.map(requestInfo =>
            requestInfo.get("name")
          );
          const successfulRequests = info.filter(
            requestInfo => !requestInfo.has("error")
          );
          this.processSucessfullyUploadedBlocks(
            action.libraryId,
            successfulRequests
          );
        }

        this.blocks = this.blocks.filter(blockInfo => {
          if (failedNames.includes(blockInfo.name)) {
            const blockId = blockInfo.id;
            // end event just in case
            ProcessActions.end(blockId);

            // there's a chance this is duplicate, so we need to preserve original file
            if (blockId.startsWith("CS_")) return false;
            return true;
          }
          return true;
        });
        this.emit(BLOCKS_UPDATE);
        break;
      }
      case BlocksConstants.UPDATE_BLOCK_SUCCESS:
      case BlocksConstants.UPDATE_BLOCK_LIBRARY_SUCCESS: {
        this.emit(BLOCKS_REQUIRE_UPDATE);
        break;
      }
      case BlocksConstants.GET_BLOCK_LIBRARY_INFO_SUCCESS: {
        const { info } = action;
        this.librariesInfo[info.id] = { ...info };
        break;
      }
      default:
        break;
    }
  };

  getBlocks() {
    return this.blocks;
  }
}

BlocksStore.dispatchToken = null;
const blocksStore = new BlocksStore();

export default blocksStore;
