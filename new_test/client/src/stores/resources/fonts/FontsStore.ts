import Immutable, { List } from "immutable";
import BaseResourcesStore, { OWNER_TYPES } from "../BaseResourcesStore";
import FontResourceFile from "./FontResourceFile";
import { FontFileInterface } from "./FontsInterfaces";
import ResourceFile from "../ResourceFile";
import ResourceFolder from "../ResourceFolder";

/**
 * Fonts store
 * @class FontsStore
 */
export class FontsStore extends BaseResourcesStore {
  /**
   * @inheritdoc
   */
  protected type = "fonts";

  /**
   * @inheritdoc
   */
  protected indexDbResourceType = "FONTS";

  /**
   * @inheritdoc
   */
  protected storageOwnerTypes: OWNER_TYPES[] = [OWNER_TYPES.OWNED];

  /**
   * @inheritdoc
   */
  protected storageClassName = "FontsStore";

  /**
   * @inheritdoc
   */
  protected canCreateFoldersWithSameNames: boolean = false;

  /**
   * Type of owner of this font storage
   * @type {null|OWNER_TYPES}
   * @protected
   */
  protected currentOwnerType = OWNER_TYPES.OWNED;

  /**
   * As we have Font storage and CompanyFontsStorage, there we keep storage  thats using in this moment
   */
  static activeStorage: FontsStore | undefined = undefined;

  /**
   * @inheritdoc
   */
  public maxDirectoryesDepth: number = 2;

  /**
   * List of fonts
   * @type {Immutable.List<FontResourceFile>}
   */
  public files: Immutable.List<FontResourceFile> = List();

  /**
   * Overrided for returning propper object of class FontResourceFile
   * @override
   * @param file
   * @returns
   */
  // eslint-disable-next-line class-methods-use-this
  protected buildFileObject(file: FontFileInterface) {
    return FontResourceFile.create(file);
  }

  /**
   * Overrided for returning propper empty object of class FontResourceFile
   * @override
   * @param entity
   * @returns
   */
  protected createEmptyFile(file: File): ResourceFile {
    const emptyFont = FontResourceFile.createEmpty(file, this);

    emptyFont?.setOwnerType(this.currentOwnerType);

    return emptyFont as ResourceFile;
  }

  /**
   * Wrapper for load resource
   * @param files
   */
  loadFonts(files: FileList | File[]) {
    return this.addToUploadQueue(files);
  }

  /**
   * Wrapper for delete resource
   * @param font
   * @returns
   */
  deleteFont(font: FontResourceFile | string) {
    return this.deleteResource(font, this.currentOwnerType);
  }

  /**
   * Wrapper for download resource
   * @param font
   * @returns
   */
  fetchDownloadFont(font: FontResourceFile | string) {
    return this.downloadResource(font, this.currentOwnerType);
  }

  /**
   * Wrapper for rename resource
   * @param fontResource
   * @param newName
   */
  rename(
    fontResource: FontResourceFile | ResourceFolder | string,
    newName: string
  ) {
    this.renameResource(fontResource, newName, this.currentOwnerType);
  }

  /**
   * @inheritdoc
   */
  canSubFolderBeCreated(): boolean {
    // TODO: there should be other permissions for this action
    return super.canSubFolderBeCreated();
  }
}

const fontsStore = new FontsStore();
export default fontsStore;
