import BaseResourcesStore, { OWNER_TYPES } from "../BaseResourcesStore";
import ResourceFile from "../ResourceFile";
import ResourceFolder from "../ResourceFolder";
import userInfoStore from "../../UserInfoStore";

/**
 * Blocks store
 * @class BlocksStore
 */
export class BlocksStore extends BaseResourcesStore {
  /**
   * @inheritdoc
   */
  protected type = "blocks";

  /**
   * @inheritdoc
   */
  protected indexDbResourceType = "BLOCKS";

  /**
   * @inheritdoc
   */
  protected currentOwner = OWNER_TYPES.OWNED;

  /**
   * @inheritdoc
   */
  protected storageOwnerTypes: OWNER_TYPES[] = [
    OWNER_TYPES.OWNED,
    OWNER_TYPES.PUBLIC,
    OWNER_TYPES.ORG
  ];

  /**
   * @inheritdoc
   */
  public maxDirectoryesDepth: number = 3;

  /**
   * Wrapper for create folder for BL
   */
  createBlockLibrary(
    name: string,
    description: string,
    ownerId: string,
    ownerType: OWNER_TYPES
  ) {
    return this.createFolder(name, description, ownerType, ownerId);
  }

  /**
   * Wrapper for load resource
   * @param files
   */
  loadBlocks(files: FileList | File[]) {
    return this.addToUploadQueue(files);
  }

  /**
   * Wrapper for delete resource
   * @param block
   * @returns
   */
  deleteBlock(block: ResourceFile | ResourceFolder | string) {
    return this.deleteResource(
      block,
      this.currentFolder?.ownerType as OWNER_TYPES
    );
  }

  /**
   * @inheritdoc
   */
  protected getStorageOwnerTypes(): OWNER_TYPES[] {
    const isAdmin = userInfoStore.getUserInfo("isAdmin");

    if (isAdmin) return this.storageOwnerTypes;

    return [OWNER_TYPES.OWNED, OWNER_TYPES.PUBLIC];
  }

  /**
   * Wrapper for download resource
   * @param template
   * @returns
   */
  downloadBlock(block: ResourceFile | ResourceFolder | string) {
    return this.downloadResource(
      block,
      this.currentFolder?.ownerType as OWNER_TYPES
    );
  }

  /**
   * @inheritdoc
   */
  canSubFolderBeCreated(): boolean {
    // TODO: there should be other permissions for this action
    return super.canSubFolderBeCreated();
  }

  /**
   * @inheritdoc
   */
  public canFileBeCreated(): boolean {
    if (!this.currentFolder) return false;

    if (this.currentFolder.isRootFolder) return false;

    return true;
  }
}

const blocksStore = new BlocksStore();
export default blocksStore;
