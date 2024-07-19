import { makeObservable, observable, action } from "mobx";
import { List } from "immutable";
import { ResourceError, ResourceFileInterface } from "./BaseInterfases";
import { NO_PRIORITY, QueueUploadableInterface } from "./QueueLoader";
import BaseResourcesStore, {
  OBJECT_TYPES,
  OWNER_TYPES
} from "./BaseResourcesStore";
import ResourceFolder from "./ResourceFolder";
import MainFunctions from "../../libraries/MainFunctions";

/**
 * Resource file class
 * Define all properties and behaviour of the file
 * @implements { ResourceFileInterface, QueueUploadableInterface }
 * @class
 */
export default class ResourceFile
  implements ResourceFileInterface, QueueUploadableInterface
{
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

  _fileName: string | undefined = undefined;

  _fileSize: string | undefined = undefined;

  _fileType: string | undefined = undefined;

  storage: BaseResourcesStore | undefined = undefined;

  parentFolder: ResourceFolder | undefined | null = undefined;

  inputFile: File | undefined = undefined;

  isUploaded: boolean | undefined = undefined;

  isUploading: boolean | undefined = undefined;

  uploadProgress: number | undefined = undefined;

  uploadPriority: number | undefined = NO_PRIORITY;

  abortController: AbortController = new AbortController();

  uploadPromise: Promise<ResourceFile>;

  uploadPromiseRejecter: (reason?: unknown) => void;

  uploadPromiseResolver: (value?: unknown) => void;

  fromDb: boolean = false;

  objectType: OBJECT_TYPES = OBJECT_TYPES.FILE;

  /**
   * Protected constructor, use static method create for create new instance of file
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
      _fileName: observable,
      _fileSize: observable,
      _fileType: observable,
      // parentFolder is observable for move operation
      parentFolder: observable,
      isUploaded: observable,
      isUploading: observable,
      uploadProgress: observable,
      fromDb: observable,
      setFromDb: action,
      startUpload: action,
      stopUpload: action,
      onUploadCompleted: action,
      setUploadPriority: action,
      setUploadProgress: action,
      update: action,
      setId: action,
      setName: action,
      setOwnerType: action
    });
  }

  /**
   * Returns fiels by it`s name, it`s only for compability with SmartTable API
   * @param name
   * @returns
   */
  get(name: string): unknown {
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
   * Getter for fileName
   */
  get fileName() {
    return this._fileName;
  }

  /**
   * Getter for fileSize
   */
  get fileSize() {
    return this._fileSize;
  }

  /**
   * Getter for fileType
   */
  get fileType() {
    return this._fileType;
  }

  /**
   * Setter for id
   * @param id
   */
  setId(id: string) {
    this._id = id;
  }

  /**
   * Setter for fileName
   * @param name
   */
  setName(name: string) {
    this._fileName = name;
  }

  /**
   * OwnerType setter
   * @param ownerType
   */
  setOwnerType(ownerType: OWNER_TYPES) {
    this._ownerType = ownerType;
  }

  /**
   * This only for compatibility with SmartTable
   * @returns ResourceFile
   */
  toJS() {
    return this;
  }

  /**
   * Use this method for create new instance of file instead of constructor
   * @param { ResourceFileInterface } file
   * @param { BaseResourcesStore } storage
   * @returns
   */
  public static create(
    file: ResourceFileInterface,
    storage: BaseResourcesStore
  ) {
    const newFile = new ResourceFile();

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

    newFile.storage = storage;

    newFile.isUploaded = true;
    newFile.isUploading = false;

    return newFile;
  }

  /**
   * Creates empty file
   * Empty file creates before sending request to API
   * for showing folder in UI while request is fetching
   * @param file
   * @param storage
   * @param priority
   * @returns
   */
  public static createEmpty(
    file: File,
    storage: BaseResourcesStore,
    priority: number = NO_PRIORITY
  ): ResourceFile | undefined {
    const newFile = new ResourceFile();
    newFile._id = MainFunctions.guid();
    newFile.isUploading = false;
    newFile.isUploaded = false;
    newFile.uploadProgress = 0;
    newFile.inputFile = file;
    newFile.uploadPriority = priority;

    newFile.storage = storage;

    newFile.uploadPromise = new Promise((resolve, reject) => {
      newFile.uploadPromiseResolver = resolve;
      newFile.uploadPromiseRejecter = reject;
    });

    return newFile;
  }

  /**
   * Update file properties
   * @param { ResourceFileInterface } file
   * @returns { ResourceFile }
   */
  public update(file: ResourceFileInterface) {
    this._id = file.id;
    this._name = file.name;
    this._parent = file.parent;
    this._type = file.type;
    this._created = file.created;
    this._updated = file.updated;
    this._path = file.path;
    this._resourceType = file.resourceType;
    this._description = file.description;
    this._ownerId = file.ownerId;
    this._isOwner = file.isOwner;
    this._ownerType = file.ownerType;
    this._ownerName = file.ownerName;
    this._fileName = file.fileName;
    this._fileSize = file.fileSize;
    this._fileType = file.fileType;

    return this;
  }

  /**
   * Start upload file
   */
  startUpload() {
    this.isUploading = true;
    this.isUploaded = false;
    this.uploadProgress = 0;
    this.storage
      ?.uploadResourse(this)
      .then((result: ResourceFile) => {
        this.uploadPromiseResolver(result);
      })
      .catch((error: ResourceError) => {
        this.uploadPromiseRejecter(error);
      });
  }

  /**
   * Stop upload file
   */
  stopUpload() {
    this.isUploading = false;
    this.isUploaded = false;
    this.uploadProgress = 0;
    this.abortController.abort();
    const fileEntityes = this.storage?.files;
    const filtered = fileEntityes?.filter(
      (file: ResourceFile) => file.id === this.id
    );
    this.storage?.__updateImmutableFilesList(filtered as List<ResourceFile>);
  }

  /**
   * Action for set upload completed
   */
  onUploadCompleted() {
    this.isUploading = false;
    this.isUploaded = true;
    this.uploadProgress = 0;
    this.storage?.queueLoader?.removeEntityFromQueue(this);
  }

  /**
   * Returns form data for upload file
   * @returns { FormData }
   */
  getFormData() {
    const formData = new FormData();
    formData.append("resourceFile", this.inputFile as File);
    formData.append("description", "none");
    formData.append("name", this.inputFile?.name as string);

    return formData;
  }

  /**
   * Action for set upload priority
   * @param priority
   */
  setUploadPriority(priority: number) {
    this.uploadPriority = priority;
  }

  setFromDb(state: boolean) {
    this.fromDb = state;
  }

  getObjectType() {
    return this.objectType;
  }

  /**
   * Action for set upload progress
   * @param progress
   */
  setUploadProgress(progress: number) {
    this.uploadProgress = progress;
  }
}
