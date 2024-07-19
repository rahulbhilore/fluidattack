import { makeObservable, observable, action } from "mobx";
import Immutable, { List } from "immutable";
import {
  ResourceFolderInterface,
  ResourcePathInterface
} from "./BaseInterfases";
import BaseResourcesStore, {
  OBJECT_TYPES,
  OWNER_TYPES
} from "./BaseResourcesStore";
import ResourceFile from "./ResourceFile";
import MainFunctions from "../../libraries/MainFunctions";

export const ROOT_FOLDER = "-1";

/**
 * Resource folder class
 * Define all properties and behaviour of the folder
 * @implements { ResourceFolderInterface }
 * @class
 */
export default class ResourceFolder implements ResourceFolderInterface {
  _id: string | undefined = undefined;

  _name: string | undefined = undefined;

  _parent: string | undefined = undefined;

  _type: string | undefined = undefined;

  _created: number | undefined = undefined;

  _updated: number | undefined = undefined;

  _path: string | undefined = undefined;

  _resourceType: string | undefined = undefined;

  _description: string | undefined = undefined;

  _ownerId: string | undefined = undefined;

  _isOwner: boolean | undefined = undefined;

  _ownerType: OWNER_TYPES | undefined = undefined;

  _ownerName: string | undefined = undefined;

  isRootFolder: boolean = false;

  storage: BaseResourcesStore | undefined = undefined;

  children: Immutable.List<ResourceFolder | ResourceFile> = List();

  parentFolder: ResourceFolder | undefined | null = undefined;

  folderDepth: number = 1;

  fetchedPatch: ResourcePathInterface[] = [];

  fromDb: boolean = false;

  objectType: OBJECT_TYPES = OBJECT_TYPES.FOLDER;

  /**
   * Protected constructor, use static method create for create new instance of folder
   * @constructor
   * @protected
   */
  protected constructor() {
    makeObservable(this, {
      _id: observable,
      _name: observable,
      _parent: observable,
      _type: observable,
      _created: observable,
      _updated: observable,
      _path: observable,
      _resourceType: observable,
      _description: observable,
      _ownerId: observable,
      _isOwner: observable,
      _ownerType: observable,
      _ownerName: observable,
      fromDb: observable,
      fetchedPatch: observable,
      update: action,
      setId: action,
      setName: action,
      setFromDb: action,
      setPatch: action
    });
  }

  /**
   * Returns fiels by it`s name, it`s only for compability with SmartTable API
   * @param name
   * @returns
   */
  get(name: string) {
    // @ts-ignore
    return this[`${name}`];
  }

  /**
   * Getter for id
   */
  get id() {
    return this._id;
  }

  /**
   * Getter for name
   */
  get name() {
    return this._name;
  }

  /**
   * Getter for parent
   */
  get parent() {
    return this._parent;
  }

  /**
   * Getter for type
   */
  get type() {
    return this._type;
  }

  /**
   * Getter for created
   */
  get created() {
    return this._created;
  }

  /**
   * Getter for updated
   */
  get updated() {
    return this._updated;
  }

  /**
   * Getter for path
   */
  get path() {
    return this._path;
  }

  /**
   * Getter for resourceType
   */
  get resourceType() {
    return this._resourceType;
  }

  /**
   * Getter for description
   */
  get description() {
    return this._description;
  }

  /**
   * Getter for ownerId
   */
  get ownerId() {
    return this._ownerId;
  }

  /**
   * Getter for isOwner
   */
  get isOwner() {
    return this._isOwner;
  }

  /**
   * Getter for ownerType
   */
  get ownerType() {
    return this._ownerType;
  }

  /**
   * Getter for ownerName
   */
  get ownerName() {
    return this._ownerName;
  }

  /**
   * This only for compatibility with SmartTable
   * @returns ResourceFile
   */
  toJS() {
    return this;
  }

  /**
   * Create new instance of the class
   * Use this method for create new instance of folder instead of constructor
   * @param { ResourceFolderInterface } folderRequest
   * @param { BaseResourcesStore } storage
   * @returns { ResourceFolder }
   */
  public static create(
    folderRequest: ResourceFolderInterface,
    storage: BaseResourcesStore
  ) {
    const newFolder = new ResourceFolder();

    newFolder._id = folderRequest.id;
    newFolder._name = folderRequest.name;
    newFolder._parent = folderRequest.parent;
    newFolder._type = folderRequest.type;
    newFolder._created = folderRequest.created;
    newFolder._updated = folderRequest.updated;
    newFolder._path = folderRequest.path;
    newFolder._resourceType = folderRequest.resourceType;
    newFolder._description = folderRequest.description;
    newFolder._ownerId = folderRequest.ownerId;
    newFolder._isOwner = folderRequest.isOwner;
    newFolder._ownerType = folderRequest.ownerType;
    newFolder._ownerName = folderRequest.ownerName;

    newFolder.storage = storage;

    return newFolder;
  }

  /**
   * Setter for id
   * @param id
   */
  setId(id: string) {
    this._id = id;
  }

  /**
   * Creates root folder, it`s not received from API
   * @param storage
   * @returns
   */
  public static createRootFolder(storage: BaseResourcesStore) {
    const newRootFolder = new ResourceFolder();
    newRootFolder.isRootFolder = true;
    newRootFolder._id = ROOT_FOLDER;
    newRootFolder.parentFolder = null;
    newRootFolder.storage = storage;

    return newRootFolder;
  }

  /**
   * Creates empty folder
   * Empty folder creates before sending request to API
   * for showing folder in UI while request is fetching
   * @param name
   * @param description
   * @param parent
   * @param ownerId
   * @param isOwner
   * @param ownerType
   * @param ownerName
   * @param storage
   * @returns
   */
  public static createEmpty(
    name: string,
    description: string,
    parent: string | undefined,
    ownerId: string,
    isOwner: boolean,
    ownerType: OWNER_TYPES,
    ownerName: string,
    storage: BaseResourcesStore
  ) {
    const newRootFolder = new ResourceFolder();
    newRootFolder.isRootFolder = false;
    newRootFolder._id = MainFunctions.guid();
    newRootFolder._parent = parent;
    newRootFolder._name = name;
    newRootFolder._description = description;
    newRootFolder._ownerId = ownerId;
    newRootFolder._isOwner = isOwner;
    newRootFolder._ownerType = ownerType;
    newRootFolder._ownerName = ownerName;
    newRootFolder.storage = storage;

    return newRootFolder;
  }

  /**
   * Add child of this folder
   * Folders also should know about their children just like their storage know
   * @param child
   */
  public addChild(child: ResourceFolder | ResourceFile) {
    this.children = this.children.push(child);
  }

  /**
   * Update folder properties
   * @param folderRequest
   * @returns
   */
  update(folderRequest: ResourceFolderInterface) {
    this._id = folderRequest.id;
    this._name = folderRequest.name;
    this._parent = folderRequest.parent;
    this._type = folderRequest.type;
    this._created = folderRequest.created;
    this._updated = folderRequest.updated;
    this._path = folderRequest.path;
    this._resourceType = folderRequest.resourceType;
    this._description = folderRequest.description;
    this._ownerId = folderRequest.ownerId;
    this._isOwner = folderRequest.isOwner;
    this._ownerType = folderRequest.ownerType;
    this._ownerName = folderRequest.ownerName;

    return this;
  }

  /**
   * Setter for fileName
   * @param name
   */
  setName(name: string) {
    this._name = name;
  }

  setPatch(patch: ResourcePathInterface[]) {
    this.fetchedPatch = patch;
  }

  setFromDb(state: boolean) {
    this.fromDb = state;
  }

  getObjectType() {
    return this.objectType;
  }

  getBreadcrumbsInfo() {
    if (this.fetchedPatch.length) return this.fetchedPatch;

    let protector = 0;
    let doLoop = true;
    let targetFolder = this as ResourceFolder;
    const result: ResourcePathInterface[] = [];

    result.push({
      _id: targetFolder.id as string,
      viewOnly: false,
      name: targetFolder.name as string
    });

    while (doLoop && protector < 100) {
      const parentId = targetFolder.parent;

      if (!parentId) {
        doLoop = false;
        break;
      }

      if (parentId === ROOT_FOLDER) {
        doLoop = false;

        result.push({
          _id: ROOT_FOLDER,
          viewOnly: false,
          name: "~"
        });
        break;
      }

      const parentFolder = this.storage?.findFolderById(parentId);

      if (parentFolder) {
        targetFolder = parentFolder;

        result.push({
          _id: parentFolder.id as string,
          viewOnly: false,
          name: parentFolder.name as string
        });
      }

      protector += 1;
    }

    result.reverse();
    this.setPatch(result);

    return result;
  }

  /**
   * Returns true if sub folder can be created
   * @returns {boolean}
   */
  canSubFolderBeCreated(): boolean {
    if (!this.storage) return false;

    return this.storage?.maxDirectoryesDepth > this.folderDepth;
  }
}
