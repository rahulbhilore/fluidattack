import { makeObservable, observable } from "mobx";
import ResourceFile from "../ResourceFile";
import { FontFacesInterface, FontFileInterface } from "./FontsInterfaces";
import { FontsStore } from "./FontsStore";

/**
 * Font file class
 * @class
 */
export default class FontResourceFile extends ResourceFile {
  _faces: FontFacesInterface | undefined = undefined;

  constructor() {
    super();
    makeObservable(this, {
      _faces: observable
    });
  }

  /**
   * Getter for faces
   */
  get faces() {
    return this._faces;
  }

  get fontFamity() {
    return this._faces?.fontFamily;
  }

  get index() {
    return this._faces?.index;
  }

  get weight() {
    return this._faces?.weight;
  }

  get style() {
    return this._faces?.style;
  }

  get bold() {
    return this._faces?.bold;
  }

  get italic() {
    return this._faces?.italic;
  }

  /**
   * Create font file
   * @param file
   * @returns
   */
  public static create(file: FontFileInterface) {
    const newFile = new FontResourceFile();

    newFile._id = file.id;
    newFile._name = file.name;
    newFile._parent = file.parent;
    newFile._type = file.type;
    newFile._created = file.created;
    newFile._updated = file.updated;
    newFile._path = file.path;
    newFile._resourceType = file.resourceType;
    newFile._description = file.description;
    newFile._ownerId = file.ownerId;
    newFile._isOwner = file.isOwner;
    newFile._ownerType = file.ownerType;
    newFile._ownerName = file.ownerName;
    newFile._fileName = file.fileName;
    newFile._fileSize = file.fileSize;
    newFile._fileType = file.fileType;
    newFile._faces = file.faces?.length && file.faces[0];

    return newFile;
  }

  /**
   * Create empty font file
   * @param file
   * @param storage
   * @returns
   */
  public static createEmpty(
    file: File | undefined,
    storage: FontsStore
  ): ResourceFile | undefined {
    if (file === undefined) return undefined;

    const newFile = ResourceFile.createEmpty(file, storage);
    const newFont = new FontResourceFile();

    const newFontFile: FontResourceFile = Object.assign(newFont, newFile);

    newFontFile._faces = {
      fontFamily: undefined,
      index: undefined,
      weight: undefined,
      style: undefined,
      bold: undefined,
      italic: undefined
    };

    return newFontFile as ResourceFile;
  }

  /**
   * Update font file
   * @param font
   * @returns
   */
  public update(font: FontFileInterface) {
    super.update(font);

    this._faces = font.faces?.length && font.faces[0];

    return this;
  }
}
