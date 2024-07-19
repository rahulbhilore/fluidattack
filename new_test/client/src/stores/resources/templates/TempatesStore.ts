import BaseResourcesStore, { OWNER_TYPES } from "../BaseResourcesStore";
import ResourceFile from "../ResourceFile";

/**
 * Templates store
 * @class TemplatesStore
 */
export class TemplatesStore extends BaseResourcesStore {
  /**
   * @inheritdoc
   */
  protected type = "templates";

  /**
   * @inheritdoc
   */
  protected indexDbResourceType = "TEMPLATES";

  /**
   * @inheritdoc
   */
  protected storageOwnerTypes: OWNER_TYPES[] = [OWNER_TYPES.OWNED];

  /**
   * @inheritdoc
   */
  protected storageClassName = "TemplatesStore";

  /**
   * Type of owner of this font storage
   * @type {null|OWNER_TYPES}
   * @protected
   */
  protected currentOwnerType = OWNER_TYPES.OWNED;

  /**
   * As we have Templates storage and PublicTemplatesStorage, there we keep storage  thats using in this moment
   */
  static activeStorage: TemplatesStore | undefined = undefined;

  /**
   * @inheritdoc
   */
  protected canCreateFilesWithSameNames: boolean = false;

  /**
   * @inheritdoc
   */
  protected canCreateFoldersWithSameNames: boolean = false;

  /**
   * @inheritdoc
   */
  public maxDirectoryesDepth: number = 3;

  /**
   * @override
   * @param file
   * @returns
   */
  protected createEmptyFile(file: File): ResourceFile {
    const emptyTemplate = ResourceFile.createEmpty(file, this);

    emptyTemplate?.setOwnerType(this.currentOwnerType);

    return emptyTemplate as ResourceFile;
  }

  /**
   * Wrapper for load resource
   * @param files
   */
  loadTemplates(files: FileList | File[]) {
    return this.addToUploadQueue(files);
  }

  /**
   * Wrapper for delete resource
   * @param template
   * @returns
   */
  deleteTemplate(template: ResourceFile | string): Promise<unknown> {
    return this.deleteResource(template, this.currentOwnerType);
  }

  /**
   * Wrapper for download resource
   * @param font
   * @returns
   */
  fetchDownloadTemplate(font: ResourceFile | string) {
    return this.downloadResource(font, this.currentOwnerType);
  }

  /**
   * Wrapper for rename template
   * @param template
   * @param newName
   * @returns
   */
  rename(template: ResourceFile | string, newName: string) {
    return this.renameResource(template, newName, this.currentOwnerType);
  }

  /**
   * @inheritdoc
   */
  canSubFolderBeCreated(): boolean {
    // TODO: there should be other permissions for this action
    return super.canSubFolderBeCreated();
  }
}

const templatesStore = new TemplatesStore();
export default templatesStore;
