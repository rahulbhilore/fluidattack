/* eslint-disable prefer-promise-reject-errors */
import { makeObservable, observable, action, computed } from "mobx";
import axios, { AxiosRequestConfig, AxiosHeaders, AxiosResponse } from "axios";
import Immutable, { List } from "immutable";
import Requests from "../../utils/Requests";
import ResourceFile from "./ResourceFile";
import ResourceFolder, { ROOT_FOLDER } from "./ResourceFolder";
import {
  ResourceFileInterface,
  ResourceFolderInterface,
  ResourcePathInterface,
  IndexDbitemInterface,
  RESOURCES_OPERATIONS_ERRORS,
  ResourceError
} from "./BaseInterfases";
import QueueLoader, { QueueUploadableInterface } from "./QueueLoader";
import ApplicationStore from "../ApplicationStore";

/**
 * Types of objects
 */
export enum OBJECT_TYPES {
  FILE = "file",
  FOLDER = "folder"
}

/**
 * Types of owners
 */
export enum OWNER_TYPES {
  PUBLIC = "PUBLIC",
  ORG = "ORG ",
  GROUP = "GROUP",
  SHARED = "SHARED",
  OWNED = "OWNED"
}

/**
 * Types of filters
 */
export enum OBJECT_FILTER {
  FILES = "FILES",
  FOLDERS = "FOLDERS"
}

const DB_NAME = "KUDO_RESOURCES_DB";
const DB_STORE_NAME = "KUDO_RESOURCES_STORE";

/**
 * Base class for all resources storages
 * @abstract
 * @class
 */
export default abstract class BaseResourcesStore {
  /**
   * Type of resource, any child class must define this property
   * @type {null | string}
   * @protected
   */
  protected type: null | string = null;

  /**
   * Type of resource in indexDb, any child class must define this property
   */
  protected indexDbResourceType: null | string = null;

  /**
   * List of files
   * Do not modify this lists outside the class!
   * @type {Array<ResourceFile>}
   */
  public files: Array<ResourceFile> = [];

  /**
   * List of folders
   * Do not modify this lists outside the class!
   * @type {Array<ResourceFolder>}
   */
  public folders: Array<ResourceFolder> = [];

  /**
   * List of owner types used in storage
   * In child class you must redefine it if you don`t need some one
   */
  protected storageOwnerTypes = [
    OWNER_TYPES.PUBLIC,
    OWNER_TYPES.OWNED,
    OWNER_TYPES.ORG,
    OWNER_TYPES.GROUP,
    OWNER_TYPES.SHARED
  ];

  /**
   * Current folder
   * @type {ResourceFolder}
   */
  currentFolder: ResourceFolder | undefined = undefined;

  /**
   * Instanse of queue loader
   * @type {QueueLoader}
   */
  public queueLoader: QueueLoader | undefined = undefined;

  /**
   * Max depth of directory tree
   * You shoud redefine it in child class if you wanted to limit depth of directories tree
   * @type {number}
   */
  public maxDirectoryesDepth = Infinity;

  /**
   * Flag that defines that resource is in process of fetching
   * @type {boolean}
   */
  public fetchResourcesInProcess = false;

  /**
   * Flag that defines that resource is in process of deleting
   * @type {boolean}
   */
  public fetchDeleteResourceInProcess = false;

  /**
   * Flag that defines that resource is in process of getting info
   * @type {boolean}
   */
  public fetchResourceInfoInProcess = false;

  /**
   * Flag that defines that resource is in process of creating
   * @type {boolean}
   */
  public fetchCreateResourceInProcess = false;

  /**
   * Flag that defines that resource is in process of updating
   * @type {boolean}
   */
  public fetchUpdateResourceInProcess = false;

  /**
   * Flag that defines that resource is in process of downloading
   * @type {boolean}
   */
  public fetchDownloadResourceInProcess = false;

  /**
   * Flag that defines that resource folder is in process of fetching path
   * @type {boolean}
   */
  public fetchFolderResourcePathInProgress = false;

  private static _isIndexDbInitialized = false;

  private static _isDbReady = false;

  private static _indexDB: IDBDatabase | undefined;

  private _isTreeFromIndexDbBuilded = false;

  protected storageClassName = "BaseResourcesStore";

  protected canCreateFoldersWithSameNames = true;

  protected canCreateFilesWithSameNames = true;

  /**
   * Constructor
   */
  constructor() {
    makeObservable(this, {
      files: observable,
      folders: observable,
      filesAndFolders: computed,
      currentFolder: observable,
      setCurrentFolder: action,
      __pushToImmutableFilesList: action,
      __pushToImmutableFoldersList: action,
      __updateImmutableFilesList: action,
      __updateImmutableFoldersList: action,
      fetchResourcesInProcess: observable,
      fetchDeleteResourceInProcess: observable,
      fetchResourceInfoInProcess: observable,
      fetchCreateResourceInProcess: observable,
      fetchUpdateResourceInProcess: observable,
      fetchDownloadResourceInProcess: observable,
      fetchFolderResourcePathInProgress: observable,
      __setFetchResourcesInProcess: action,
      __setFetchDeleteResourceInProcess: action,
      __setFetchResourceInfoInProcess: action,
      __setFetchCreateResourceInProcess: action,
      __setFetchUpdateResourceInProcess: action,
      __setFetchDownloadResourceInProcess: action,
      __setFetchFolderResourcePathInProgress: action
    });

    this.queueLoader = new QueueLoader(this);
    const rootFolder = this.buildRootFolder();
    this.folders.push(rootFolder);
    this.setCurrentFolder(rootFolder);

    if (!BaseResourcesStore._isIndexDbInitialized) {
      BaseResourcesStore._initDB();
      BaseResourcesStore._isIndexDbInitialized = true;
    }
  }

  /**
   * Initializes indexDB
   */
  private static _initDB() {
    const openRequest = indexedDB.open(DB_NAME, 1);

    openRequest.onsuccess = () => {
      BaseResourcesStore._isDbReady = true;
      BaseResourcesStore._indexDB = openRequest.result;
    };

    openRequest.onupgradeneeded = () => {
      const db = openRequest.result;
      if (!db.objectStoreNames.contains(DB_STORE_NAME)) {
        const dbStore = db.createObjectStore(DB_STORE_NAME, {
          keyPath: "fullId"
        });
        dbStore.createIndex("resource_type_idx", "indexDbType");
      }
    };
  }

  /**
   * Method for writing data to indexDB
   * @param data
   * @returns
   */
  private _writeToIndexDb(
    data: ResourceFolderInterface[] | ResourceFileInterface[]
  ) {
    if (!BaseResourcesStore._isDbReady || !BaseResourcesStore._indexDB) return;

    const readTransaction = BaseResourcesStore._indexDB.transaction([
      DB_STORE_NAME
    ]);
    const resourcesDB = readTransaction.objectStore(DB_STORE_NAME);
    const index = resourcesDB.index("resource_type_idx");

    const request = index.getAll(this.indexDbResourceType);

    request.onsuccess = () => {
      const { result } = request;

      const foldersFromRequest = data.filter(
        (item: ResourceFolderInterface | ResourceFileInterface) =>
          item.type === OBJECT_TYPES.FOLDER
      );

      const filesFromRequest = data.filter(
        (item: ResourceFolderInterface | ResourceFileInterface) =>
          item.type === OBJECT_TYPES.FILE
      );

      let currentParentId: string | undefined;

      if (foldersFromRequest.length)
        currentParentId = foldersFromRequest[0].parent;
      else currentParentId = filesFromRequest[0].parent;

      const folderResourceInDb = result.filter(
        (item: ResourceFolderInterface) =>
          item.type === OBJECT_TYPES.FOLDER && item.parent === currentParentId
      );

      const filesResourceInDb = result.filter(
        (item: ResourceFileInterface) =>
          item.type === OBJECT_TYPES.FILE && item.parent === currentParentId
      );

      if (!BaseResourcesStore._isDbReady || !BaseResourcesStore._indexDB)
        return;

      const writeTransaction = BaseResourcesStore._indexDB.transaction(
        [DB_STORE_NAME],
        "readwrite"
      );
      const dbStore = writeTransaction.objectStore(DB_STORE_NAME);

      folderResourceInDb.forEach((item: IndexDbitemInterface) => {
        dbStore.delete(item.fullId as string);
      });

      filesResourceInDb.forEach((item: IndexDbitemInterface) => {
        dbStore.delete(item.fullId as string);
      });

      data.forEach((item: ResourceFolderInterface | ResourceFileInterface) => {
        const dbObject = {
          fullId: `${this.indexDbResourceType}_${item.id}`,
          indexDbType: this.indexDbResourceType,
          ...item
        };

        dbStore.put(dbObject);
      });
    };
  }

  /**
   * Remove resources from indexDB
   * @param data
   * @returns
   */
  private _deleteFromIndexDb(
    data: ResourceFile | ResourceFolder | Array<ResourceFolder | ResourceFile>
  ) {
    if (!BaseResourcesStore._isDbReady || !BaseResourcesStore._indexDB) return;

    const writeTransaction = BaseResourcesStore._indexDB.transaction(
      [DB_STORE_NAME],
      "readwrite"
    );
    const dbStore = writeTransaction.objectStore(DB_STORE_NAME);

    if (Array.isArray(data)) {
      data.forEach((item: ResourceFolder | ResourceFile) => {
        const fullId = `${this.indexDbResourceType}_${item.id}`;
        dbStore.delete(fullId as string);
      });
    } else {
      const fullId = `${this.indexDbResourceType}_${data.id}`;
      dbStore.delete(fullId as string);
    }
  }

  /**
   * Update item in indexDb
   * @param data
   * @returns {void}
   */
  private _updateItemInIndexDb(data: ResourceFile | ResourceFolder) {
    if (!BaseResourcesStore._isDbReady || !BaseResourcesStore._indexDB) return;

    const writeTransaction = BaseResourcesStore._indexDB.transaction(
      [DB_STORE_NAME],
      "readwrite"
    );
    const dbStore = writeTransaction.objectStore(DB_STORE_NAME);

    const dbObject = {
      fullId: `${this.indexDbResourceType}_${data.id}`,
      indexDbType: this.indexDbResourceType,
      ...data
    };

    dbStore.put(dbObject);
  }

  /**
   * Builds resources tree from indexDB
   * @returns
   */
  private _buildTreeFromIndexDb() {
    if (!BaseResourcesStore._isDbReady || !BaseResourcesStore._indexDB) return;

    if (this._isTreeFromIndexDbBuilded) return;
    this._isTreeFromIndexDbBuilded = true;

    const transaction = BaseResourcesStore._indexDB.transaction([
      DB_STORE_NAME
    ]);
    const resourcesDB = transaction.objectStore(DB_STORE_NAME);
    const index = resourcesDB.index("resource_type_idx");

    const request = index.getAll(this.indexDbResourceType);

    request.onsuccess = () => {
      const { result } = request;

      const folderResource = result.filter(
        (item: ResourceFolderInterface) => item.type === OBJECT_TYPES.FOLDER
      );

      const filesResource = result.filter(
        (item: ResourceFileInterface) => item.type === OBJECT_TYPES.FILE
      );

      this.parseFolders(folderResource, true);
      this.parseFiles(filesResource, true);
    };
  }

  /**
   * Creates folder object from object with ResourceFolderInterface
   * @param folder
   * @returns
   */
  protected buildFolderObject(
    folder: ResourceFolderInterface,
    fromDb: boolean = false
  ): ResourceFolder {
    const folderObject = ResourceFolder.create(folder, this);

    if (fromDb) folderObject.setFromDb(true);
    return folderObject;
  }

  /**
   * Creates file object from object with ResourceFileInterface
   * @param file
   * @returns
   */
  protected buildFileObject(
    file: ResourceFileInterface,
    fromDb: boolean = false
  ): ResourceFile {
    const fileObject = ResourceFile.create(file, this);

    if (fromDb) fileObject.setFromDb(true);
    return fileObject;
  }

  /**
   * Returns type of specific storage
   * @returns {OWNER_TYPES}
   */
  public getStorageType() {
    return this.type;
  }

  /**
   * Creates empty folder
   * You should redefine this method in child class if
   * you have extended RecourseFolder class in your child storage
   * @param name
   * @param description
   * @param parent
   * @param ownerId
   * @param isOwner
   * @param ownerType
   * @param ownerName
   * @returns
   */
  protected createEmptyFolder(
    name: string,
    description: string,
    parent: string | undefined,
    ownerId: string,
    isOwner: boolean,
    ownerType: OWNER_TYPES,
    ownerName: string
  ): ResourceFolder {
    return ResourceFolder.createEmpty(
      name,
      description,
      parent,
      ownerId,
      isOwner,
      ownerType,
      ownerName,
      this
    );
  }

  /**
   * Creates empty file
   * You should redefine this method in child class if
   * you have extended RecourseFile class in your child storage
   * (see FontFile class for example)
   * @param entity
   * @returns
   */
  protected createEmptyFile(file: File): ResourceFile {
    const emptyFile = ResourceFile.createEmpty(file, this);

    emptyFile?.setOwnerType(this.currentFolder?.ownerType as OWNER_TYPES);

    return emptyFile as ResourceFile;
  }

  /**
   * Creates root folder that`s used for system needes
   * @returns
   */
  protected buildRootFolder(): ResourceFolder {
    return ResourceFolder.createRootFolder(this);
  }

  /**
   * Sets current folder
   * Current folder is folder where you are right now
   * @param folder
   */
  setCurrentFolder(folder: ResourceFolder) {
    this.currentFolder = folder;
  }

  /**
   * I.A: MobX do not allow to use private and protected methods as actions
   * So this strange name avoid anyone to use this methods outside the class
   */
  public __pushToImmutableFilesList(file: ResourceFile) {
    this.files.push(file);
  }

  public __pushToImmutableFoldersList(folder: ResourceFolder) {
    this.folders.push(folder);
  }

  public __updateImmutableFilesList(newFilesList: Array<ResourceFile>) {
    this.files = newFilesList;
  }

  public __updateImmutableFoldersList(newFoldersList: Array<ResourceFolder>) {
    this.folders = newFoldersList;
  }

  /**
   * Method to parse folders from request or indexDb in store
   * @param folders
   */
  private parseFolders(
    folders: ResourceFolderInterface[],
    fromDb: boolean,
    ownerType?: OWNER_TYPES
  ) {
    const foldersShouldBeCreated = folders.filter(
      folderResource => !this.findFolderById(folderResource.id)
    );
    const foldersShouldBeUpdated = folders.filter(folderResource =>
      this.findFolderById(folderResource.id)
    );

    const newFoldersList = foldersShouldBeCreated.map(folderResource =>
      this.buildFolderObject(folderResource, fromDb)
    );

    newFoldersList.forEach((folder: ResourceFolder) => {
      const splittedPath = folder.path?.split("/");
      folder.folderDepth = splittedPath?.length as number;
    });

    if (newFoldersList.length) {
      const mergedList = this.folders.concat(newFoldersList);
      this.__updateImmutableFoldersList(mergedList);
    }

    foldersShouldBeUpdated.forEach(folderResource => {
      const folder = this.findFolderById(folderResource.id);

      if (folder) folder.update(folderResource);
    });
  }

  /**
   * Method to parse files from request or indexDb in store
   * @param files
   */
  parseFiles(
    files: ResourceFileInterface[],
    fromDb: boolean,
    ownerType?: OWNER_TYPES
  ) {
    const filesShouldBeCreated = files.filter(
      fileResource => !this.findFileById(fileResource.id)
    );
    const filesShouldBeUpdated = files.filter(fileResource =>
      this.findFileById(fileResource.id)
    );
    const newFilesList = filesShouldBeCreated.map(fileResource =>
      this.buildFileObject(fileResource, fromDb)
    );

    newFilesList.forEach(file => {
      const parentFolder = this.findFolderById(file.parent);

      if (parentFolder) parentFolder.addChild(file);
    });

    if (newFilesList.length) {
      const mergedList = this.files.concat(newFilesList);
      this.__updateImmutableFilesList(mergedList);
    }

    filesShouldBeUpdated.forEach(fileResource => {
      const file = this.findFileById(fileResource.id);

      if (file) file.update(fileResource);
    });
  }

  /**
   * Add file to upload queue
   * @param file
   */
  protected addToUploadQueue(
    file: File | FileList | Array<File>
  ): Promise<ResourceFile>[] {
    if (file instanceof FileList || Array.isArray(file)) {
      const resources: ResourceFile[] = [];
      for (let i = 0; i < file.length; i += 1) {
        const resourceFile = this.createEmptyFile(file[i]);

        resources.push(resourceFile);
        this.queueLoader?.addEntityToQueue(resourceFile);
      }

      const promises = resources.map(resource => resource.uploadPromise);
      return promises;
    }

    const resourceFile = ResourceFile.createEmpty(file, this);
    this.queueLoader?.addEntityToQueue(resourceFile as ResourceFile);
    return [resourceFile?.uploadPromise as Promise<ResourceFile>];
  }

  /**
   * Get folder by id
   * @param folderId
   * @returns
   */
  public findFolderById(folderId: string | undefined) {
    if (!folderId) return null;

    return this.folders.find((itemFolder: ResourceFolder) => {
      if (!itemFolder) return false;

      const { id } = itemFolder;
      return id === folderId;
    });
  }

  /**
   * Get file by id
   * @param file
   * @returns
   */
  public findFileById(file: ResourceFile | string | undefined) {
    if (file === undefined) return undefined;

    const fileId = file instanceof ResourceFile ? file.id : file;

    return this.files.find((itemFile: ResourceFile) => {
      if (itemFile === undefined) return false;

      const { id } = itemFile;
      return id === fileId;
    });
  }

  /**
   * Returns true if sub-folder can be created
   * @returns {bool}
   */
  public canSubFolderBeCreated(): boolean {
    if (!this.currentFolder) return false;

    return this.currentFolder.canSubFolderBeCreated();
  }

  /**
   * Returns true if file can be created
   * Redefine it in subclass for change this behavior
   * @returns
   */
  public canFileBeCreated(): boolean {
    if (!this.currentFolder) return false;

    return true;
  }

  /**
   * Builds proper axios header
   * @returns {AxiosHeaders}
   */
  // eslint-disable-next-line class-methods-use-this
  private getAxiosHeaderObject(): AxiosHeaders {
    const axiosHeader = new AxiosHeaders();
    const oldHeaders = Requests.getDefaultUserHeaders();

    axiosHeader.set("sessionId", oldHeaders.sessionId);
    axiosHeader.set("locale", oldHeaders.locale);

    return axiosHeader;
  }

  /**
   * Returns proper server url
   * @param shortUrl
   * @returns
   */
  // eslint-disable-next-line class-methods-use-this
  protected buildFinalServerURL(shortUrl: string): string {
    let finalServerURL = shortUrl;
    finalServerURL =
      ApplicationStore.getApplicationSetting("apiURL") + finalServerURL;

    return finalServerURL;
  }

  /**
   * Returns name of storage class
   * @returns {string}
   */
  getStorageName(): string {
    return this.storageClassName;
  }

  /**
   * Sets observable flag to show that resource is in process of fetching
   * @param value
   */
  public __setFetchResourcesInProcess(value: boolean) {
    this.fetchResourcesInProcess = value;
  }

  /**
   * Get all files and folders inside current folder
   * @return {Array<ResourceFolder | ResourceFile>}
   */
  get filesAndFolders(): Immutable.List<ResourceFolder | ResourceFile> {
    const { files, folders } = this;

    if (!this.currentFolder)
      return Immutable.List<ResourceFolder | ResourceFile>();

    const filteredFolders = folders.filter(
      (folder: ResourceFolder) => folder.parent === this.currentFolder?.id
    );
    const filteredFiles = files.filter(
      (file: ResourceFile) => file.parent === this.currentFolder?.id
    );

    return Immutable.List(filteredFolders).concat(
      Immutable.List(filteredFiles)
    );
  }

  /**
   * Get all objects inside any folder of a resource
   * @param folderId
   * @param ownerType
   * @param ownerId
   * @param objectFilter
   * @protected
   */
  protected fetchResource(
    folder: ResourceFolder | string,
    ownerType: OWNER_TYPES,
    ownerId?: string,
    objectFilter?: OBJECT_TYPES
  ) {
    return new Promise((resolve, reject) => {
      this.__setFetchResourcesInProcess(true);

      let folderId = "-1";
      if (folder instanceof ResourceFolder) folderId = folder.id || "-1";
      else folderId = folder;

      const axiosHeader = this.getAxiosHeaderObject();
      axiosHeader.set("ownerType", ownerType);

      if (ownerId) axiosHeader.set("ownerId", ownerId);
      if (objectFilter) axiosHeader.set("objectFilter", objectFilter);

      const axiosConfig: AxiosRequestConfig = {
        method: "GET",
        url: this.buildFinalServerURL(
          `/resources/${this.type}/${folderId}/items`
        ),
        headers: axiosHeader
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data } = response;
          const { results } = data;
          const { folders, files } = results;

          if (folders.length || files.length)
            this._writeToIndexDb([...folders, ...files]);

          if (folders.length) this.parseFolders(folders, false, ownerType);

          if (files.length) this.parseFiles(files, false, ownerType);

          resolve(results);
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        })
        .finally(() => {
          this.__setFetchResourcesInProcess(false);
        });
    });
  }

  __setFetchCreateResourceInProcess(value: boolean) {
    this.fetchCreateResourceInProcess = value;
  }

  /**
   * Create object inside any folder of a resource
   * @param folderId
   * @param ownerType
   * @param requestBody
   * @param ownerId
   * @protected
   */
  protected fetchCreateResource(
    entity: ResourceFile | ResourceFolder | null,
    folderId: string | undefined,
    ownerType: OWNER_TYPES,
    requestBody: FormData,
    objectType: OBJECT_TYPES = OBJECT_TYPES.FILE,
    ownerId?: string
  ): Promise<AxiosResponse> {
    if (!entity)
      return new Promise((resolve, reject) => {
        reject({
          type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
          value: "No entity in fetchCreateResource request"
        } as ResourceError);
      });

    return new Promise((resolve, reject) => {
      const axiosHeader = this.getAxiosHeaderObject();
      axiosHeader.set("ownerType", ownerType);

      axiosHeader.set("objectType", objectType);
      axiosHeader.set("Content-Type", "multipart/form-data");

      if (ownerId) axiosHeader.set("ownerId", ownerId);

      const axiosConfig: AxiosRequestConfig = {
        method: "POST",
        url: this.buildFinalServerURL(
          `/resources/${this.type}/${folderId}/items`
        ),
        headers: axiosHeader,
        data: requestBody,
        onUploadProgress: progressEvent => {
          if (!entity) return;

          const { progress } = progressEvent;

          if ("setUploadProgress" in entity)
            entity?.setUploadProgress(Math.round((progress || 0) * 100));
        },
        signal:
          "setUploadProgress" in entity
            ? entity?.abortController.signal
            : undefined
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data } = response;
          const { objectId } = data;

          if ("onUploadCompleted" in entity) entity?.onUploadCompleted();

          entity?.setId(objectId);
          resolve(response);
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        });
    });
  }

  /**
   * Upload resource file to the server
   * @param entity
   * @param ownerType
   * @returns
   */
  uploadResourse(entity: QueueUploadableInterface) {
    if (entity.inputFile === null)
      return new Promise((resolve, reject) => {
        reject({
          type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
          value: "No inputFile in QueueUploadableInterface"
        } as ResourceError);
      });

    if (!entity.id)
      return new Promise((resolve, reject) => {
        reject({
          type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
          value: "No id in QueueUploadableInterface"
        } as ResourceError);
      });

    const inputFile = entity.inputFile as File;

    if (!this.canCreateFilesWithSameNames) {
      const parentId = this.currentFolder?.id;

      if (!parentId)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
            value: "No currentFolder"
          } as ResourceError);
        });

      const filesInCurrentFolder = this.files.filter(
        (file: ResourceFile) => file.parent === parentId
      );

      const filesWithSameName = filesInCurrentFolder.find(
        (file: ResourceFile) => file.name === inputFile.name
      );

      if (filesWithSameName)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.FILE_NAME_DUPLICATED,
            value: inputFile.name
          } as ResourceError);
        });
    }

    const emptyFile = this.createEmptyFile(entity.inputFile as File);

    if (!emptyFile)
      return new Promise((resolve, reject) => {
        reject({
          type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
          value: "Can`t create empty file"
        } as ResourceError);
      });

    emptyFile._id = entity.id;
    emptyFile._name = inputFile.name;
    emptyFile._type = OBJECT_TYPES.FILE;
    emptyFile._fileName = inputFile.name;
    emptyFile._parent = this.currentFolder?.id || "-1";
    emptyFile.parentFolder = this.currentFolder;

    this.__pushToImmutableFilesList(emptyFile);

    return this.fetchCreateResource(
      emptyFile,
      this.currentFolder?.id,
      emptyFile.ownerType as OWNER_TYPES,
      entity.getFormData(),
      OBJECT_TYPES.FILE
    ).then(() => this.getResourceInfo(emptyFile, true));
  }

  /**
   * Creates folder
   * @param name
   * @param description
   * @param ownerId
   * @param ownerType
   */
  createFolder(
    name: string,
    description: string,
    ownerType: OWNER_TYPES,
    ownerId: string
  ): Promise<AxiosResponse> {
    if (!this.canCreateFoldersWithSameNames) {
      const parentId = this.currentFolder?.id;
      if (!parentId)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
            value: "No currentFolder"
          } as ResourceError);
        });

      const foldersInCurrentFolder = this.folders.filter(
        (folder: ResourceFolder) => folder.parent === parentId
      );

      const folderWithSameName = foldersInCurrentFolder.find(
        (folder: ResourceFolder) => folder.name === name
      );

      if (folderWithSameName)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.FOLDER_NAME_DUPLICATED,
            value: name
          } as ResourceError);
        });
    }

    const formData = new FormData();
    formData.append("name", name);
    formData.append("description", description);

    // TODO:
    // This is strange behavior of API to demand file for
    // creating folder, it`s should be removed on server side
    const blob = new Blob([""], { type: "plain/text" });
    const emptyFile = new File([blob], "empty");
    formData.append("resourceFile", emptyFile);

    const newFolder = this.createEmptyFolder(
      name,
      description,
      this.currentFolder?.id,
      ownerId,
      true,
      ownerType,
      "ownerName"
    );

    this.__pushToImmutableFoldersList(newFolder);

    return this.fetchCreateResource(
      newFolder,
      this.currentFolder?.id,
      ownerType,
      formData,
      OBJECT_TYPES.FOLDER
    ).then(() => this.getResourceInfo(newFolder, true));
  }

  /**
   * Action for set flag to show that resource is in process of deleting
   * @param value
   */
  public __setFetchDeleteResourceInProcess(value: boolean) {
    this.fetchDeleteResourceInProcess = value;
  }

  /**
   * Implements downloading of the resource
   * @param block
   * @param ownerType
   * @returns
   */
  public downloadResource(
    block: ResourceFile | ResourceFolder | string,
    ownerType: OWNER_TYPES
  ) {
    let resourceInstance: ResourceFile | ResourceFolder | undefined;

    if (!this.currentFolder?.id)
      return new Promise((resolve, reject) => {
        reject({
          type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
          value: "Current folder is not defined"
        } as ResourceError);
      });

    if (!(block instanceof ResourceFile || block instanceof ResourceFolder)) {
      resourceInstance = this.files.find((item: ResourceFile) => {
        if (item === undefined) return false;
        return item.id === block;
      });

      if (!resourceInstance) {
        resourceInstance = this.folders.find((item: ResourceFolder) => {
          if (item === undefined) return false;
          return item.id === block;
        });
      }

      if (resourceInstance === undefined)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
            value: "No resource instanse found"
          } as ResourceError);
        });
    } else {
      resourceInstance = block;
    }

    return this.fetchDownloadResource(
      resourceInstance,
      ownerType,
      resourceInstance.type as OBJECT_TYPES
    );
  }

  /**
   * Delete resource objects
   * @param folderId
   * @param ownerType
   * @param ownerId
   * @protected
   */
  protected fetchDeleteResource(
    resource:
      | ResourceFile
      | ResourceFolder
      | Array<ResourceFile | ResourceFolder>,
    ownerType: OWNER_TYPES,
    ownerId?: string
  ) {
    return new Promise((resolve, reject) => {
      this.__setFetchDeleteResourceInProcess(true);

      const axiosHeader = this.getAxiosHeaderObject();

      axiosHeader.set("ownerType", ownerType);
      axiosHeader.set("Content-type", "application/json");

      if (ownerId) axiosHeader.set("ownerId", ownerId);

      let data = [];

      if (Array.isArray(resource)) {
        data = resource.map(item => ({
          id: item?.id,
          objectType: item?.type
        }));
      } else {
        data = [
          {
            id: resource?.id,
            objectType: resource?.type
          }
        ];
      }

      const axiosConfig: AxiosRequestConfig = {
        method: "PUT",
        url: this.buildFinalServerURL(`/resources/${this.type}/items/trash`),
        headers: axiosHeader,
        data
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data: respData } = response;
          const { results } = respData;
          if (!Array.isArray(resource)) {
            if (resource?._type === OBJECT_TYPES.FILE) {
              const filteredFiles = this.files.filter((item: ResourceFile) => {
                if (item === undefined) return false;
                return item.id !== resource?.id;
              });
              this._deleteFromIndexDb(resource);
              this.__updateImmutableFilesList(List(filteredFiles));
            }

            if (resource?._type === OBJECT_TYPES.FOLDER) {
              const filteredFolders = this.folders.filter(
                (item: ResourceFolder) => {
                  if (item === undefined) return false;
                  return item.id !== resource?.id;
                }
              );
              this._deleteFromIndexDb(resource);
              this.__updateImmutableFoldersList(List(filteredFolders));
            }
          }

          resolve(results);
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        })
        .finally(() => {
          this.__setFetchDeleteResourceInProcess(false);
        });
    });
  }

  /**
   * Implements deleting of resource
   * @param resource
   * @param ownerType
   * @returns
   */
  public deleteResource(
    resource: ResourceFile | ResourceFolder | string,
    ownerType: OWNER_TYPES
  ) {
    let resourceInstance: ResourceFile | ResourceFolder | undefined;

    if (
      !(resource instanceof ResourceFile || resource instanceof ResourceFolder)
    ) {
      resourceInstance = this.files.find((item: ResourceFile) => {
        if (item === undefined) return false;
        return item.id === resource;
      });

      if (!resourceInstance) {
        resourceInstance = this.folders.find((item: ResourceFolder) => {
          if (item === undefined) return false;
          return item.id === resource;
        });
      }

      if (resourceInstance === undefined)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
            value: "No resource instanse found"
          } as ResourceError);
        });
    } else {
      resourceInstance = resource;
    }

    return this.fetchDeleteResource(
      resourceInstance,
      resourceInstance._ownerType as OWNER_TYPES
    );
  }

  /**
   * Action for set flag to show that resource is in process of getting info
   * @param value
   */
  __setFetchResourceInfoInProcess(value: boolean) {
    this.fetchResourceInfoInProcess = value;
  }

  /**
   * Get resource object info
   * @param folderId
   * @param itemId
   * @param ownerType
   * @param objectType
   * @param ownerId
   * @protected
   */
  protected fetchResourceInfo(
    resourceId: string | undefined,
    objectType: OBJECT_TYPES,
    resource?: ResourceFile | ResourceFolder | undefined,
    ownerId?: string
  ) {
    return new Promise((resolve, reject) => {
      this.__setFetchResourceInfoInProcess(true);

      const axiosHeader = this.getAxiosHeaderObject();
      axiosHeader.set("objectType", objectType);

      if (ownerId) axiosHeader.set("ownerId", ownerId);

      const axiosConfig: AxiosRequestConfig = {
        method: "GET",
        url: this.buildFinalServerURL(
          `/resources/${this.type}/items/${resourceId}/info`
        ),
        headers: axiosHeader
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data } = response;

          if (resource) {
            resource.update(data);
            resolve(data);
            return;
          }

          const findResource = this.findFolderById(resourceId);

          this._updateItemInIndexDb(data);

          if (findResource) {
            findResource.update(data);
            resolve(data);
          }

          if (objectType === OBJECT_TYPES.FOLDER) {
            const newFolder = this.buildFolderObject(data);
            this.__pushToImmutableFoldersList(newFolder);
            resolve(data);
          }

          if (objectType === OBJECT_TYPES.FILE) {
            const newFile = this.buildFileObject(data);
            this.__pushToImmutableFilesList(newFile);
            resolve(data);
          }
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        })
        .finally(() => {
          this.__setFetchResourceInfoInProcess(false);
        });
    });
  }

  /**
   * Get resource info for known resource
   * @param resource
   * @returns
   */
  public getResourceInfo(
    resource: ResourceFile | ResourceFolder | string,
    update: boolean = false
  ) {
    let resourceInstance: ResourceFile | ResourceFolder | undefined;

    if (
      !(resource instanceof ResourceFile || resource instanceof ResourceFolder)
    ) {
      resourceInstance = this.files.find((item: ResourceFile) => {
        if (item === undefined) return false;
        return item.id === resource;
      });

      if (!resourceInstance) {
        resourceInstance = this.folders.find((item: ResourceFolder) => {
          if (item === undefined) return false;
          return item.id === resource;
        });
      }

      if (resourceInstance === undefined)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
            value: "No resource instanse found"
          } as ResourceError);
        });
    } else {
      resourceInstance = resource;
    }

    if (update) {
      return this.fetchResourceInfo(
        resourceInstance?.id,
        resourceInstance.getObjectType(),
        resourceInstance
      );
    }

    return this.fetchResourceInfo(
      resourceInstance?.id,
      resourceInstance.getObjectType()
    );
  }

  /**
   * Can be redefined in child classes for return storage owner types
   * with special conditions
   * @returns
   */
  protected getStorageOwnerTypes() {
    return this.storageOwnerTypes;
  }

  /**
   * Used for traveling via folders
   * @param folderId
   * @returns
   */
  public loadFolderInfo(folderIdArg?: string) {
    let folderId: string;

    if (!folderIdArg) {
      folderId = ROOT_FOLDER;
    } else {
      folderId = folderIdArg;
    }

    this._buildTreeFromIndexDb();

    return new Promise((resolve, reject) => {
      const promises: Promise<unknown>[] = [];

      if (folderId === ROOT_FOLDER) {
        this.getStorageOwnerTypes().forEach(ownerType => {
          promises.push(this.fetchResource(folderId as string, ownerType));
        });

        const rootFolder = this.findFolderById(folderId) as ResourceFolder;
        this.setCurrentFolder(rootFolder);

        Promise.all(promises)
          .then(result => {
            resolve(result);
          })
          .catch(error => {
            reject({
              type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
              ...error
            } as ResourceError);
          });

        return;
      }

      const folder = this.findFolderById(folderId);

      if (folder) {
        promises.push(
          this.fetchResource(
            folderId as string,
            folder?.ownerType as OWNER_TYPES
          )
        );
        Promise.all(promises)
          .then(result => {
            this.setCurrentFolder(folder);
            resolve(result);
          })
          .catch(error => {
            reject({
              type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
              ...error
            } as ResourceError);
          });

        return;
      }

      promises.push(this.fetchResourceInfo(folderId, OBJECT_TYPES.FOLDER));
      promises.push(this.fetchFolderResourcePath(folderId as string));

      Promise.all(promises)
        .then(response => {
          const findedFolder = this.findFolderById(folderId);
          this.setCurrentFolder(findedFolder as ResourceFolder);
          const path = response[1] as ResourcePathInterface[];
          findedFolder?.setPatch(path.reverse());

          this.fetchResource(
            folderId as string,
            findedFolder?.ownerType as OWNER_TYPES
          )
            .then(() => {
              resolve(response);
            })
            .catch(error => {
              reject({
                type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
                ...error
              } as ResourceError);
            });
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        });
    });
  }

  /**
   * Action for set flag to show that resource is in process of updating
   * @param value
   */
  __setFetchUpdateResourceInProcess(value: boolean) {
    this.fetchUpdateResourceInProcess = value;
  }

  /**
   * Update resource object
   * @param folderId
   * @param itemId
   * @param ownerType
   * @param objectType
   * @param requestBody
   * @param ownerId
   * @protected
   */
  protected fetchUpdateResource(
    folderId: string,
    resource: ResourceFile | ResourceFolder | undefined,
    ownerType: OWNER_TYPES,
    objectType: OBJECT_TYPES,
    requestBody: FormData,
    ownerId?: string
  ) {
    return new Promise((resolve, reject) => {
      this.__setFetchUpdateResourceInProcess(true);

      const axiosHeader = this.getAxiosHeaderObject();

      axiosHeader.set("ownerType", ownerType);
      axiosHeader.set("objectType", objectType);

      if (ownerId) axiosHeader.set("ownerId", ownerId);

      const axiosConfig: AxiosRequestConfig = {
        method: "PUT",
        url: this.buildFinalServerURL(
          `/resources/${this.type}/${folderId}/items/${resource?.id}`
        ),
        headers: axiosHeader,
        data: requestBody
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data } = response;
          const { results } = data;
          resolve(results);
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        })
        .finally(() => {
          this.__setFetchUpdateResourceInProcess(false);
        });
    });
  }

  /**
   * Rename resource
   * @param resource
   * @param newName
   * @param ownerType
   * @returns
   */
  renameResource(
    resource: ResourceFile | ResourceFolder | string,
    newName: string,
    ownerType: OWNER_TYPES
  ) {
    let resourceInstance: ResourceFile | ResourceFolder | undefined;

    if (
      !(resource instanceof ResourceFile || resource instanceof ResourceFolder)
    ) {
      resourceInstance = this.files.find((item: ResourceFile) => {
        if (item === undefined) return false;
        return item.id === resource;
      });

      if (!resourceInstance) {
        resourceInstance = this.folders.find((item: ResourceFolder) => {
          if (item === undefined) return false;
          return item.id === resource;
        });
      }

      if (resourceInstance === undefined)
        return new Promise((resolve, reject) => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.INTERNAL_ERROR,
            value: "No resource instanse found"
          } as ResourceError);
        });
    } else {
      resourceInstance = resource;
    }

    const formData = new FormData();
    formData.append("name", newName as string);

    const oldName = resourceInstance.name || "";
    resourceInstance.setName(newName);

    const isFolder = resourceInstance.getObjectType() === OBJECT_TYPES.FOLDER;

    return this.fetchUpdateResource(
      this.currentFolder?._id as string,
      resourceInstance,
      ownerType,
      isFolder ? OBJECT_TYPES.FOLDER : OBJECT_TYPES.FILE,
      formData
    ).catch(() => {
      if (!resourceInstance) return;

      resourceInstance.setName(oldName);
    });
  }

  /**
   * Action for set flag to show that resource is in process of downloading
   * @param value
   */
  __setFetchDownloadResourceInProcess(value: boolean): void {
    this.fetchDownloadResourceInProcess = value;
  }

  /**
   * Download resource object
   * @param folderId
   * @param resource
   * @param ownerType
   * @param objectType
   * @param ownerId
   * @param recursive
   * @protected
   */
  protected fetchDownloadResource(
    resource: ResourceFile | ResourceFolder | undefined,
    ownerType: OWNER_TYPES,
    objectType: OBJECT_TYPES,
    ownerId?: string,
    recursive?: boolean
  ) {
    return new Promise((resolve, reject) => {
      this.__setFetchDownloadResourceInProcess(true);

      const axiosHeader = this.getAxiosHeaderObject();

      axiosHeader.set("ownerType", ownerType);
      axiosHeader.set("objectType", objectType);

      if (ownerId) axiosHeader.set("ownerId", ownerId);

      const axiosConfig: AxiosRequestConfig = {
        method: "GET",
        url: this.buildFinalServerURL(
          `/resources/${this.type}/items/${resource?.id}/content`
        ),
        headers: axiosHeader
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data } = response;
          const blob = new Blob([data], { type: "application/octet-stream" });

          resolve({
            code: response.status,
            contentType: "application/octet-stream",
            data: blob,
            headers: response.headers
          });
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        })
        .finally(() => {
          this.__setFetchDownloadResourceInProcess(true);
        });
    });
  }

  /**
   * Action for set flag to show that resource folder is in process of fetching
   * @param value
   */
  __setFetchFolderResourcePathInProgress(value: boolean): void {
    this.fetchFolderResourcePathInProgress = value;
  }

  /**
   * Fetch folder path
   * @param folderId
   * @returns
   */
  protected fetchFolderResourcePath(folderId: string) {
    return new Promise((resolve, reject) => {
      this.__setFetchFolderResourcePathInProgress(true);

      const axiosHeader = this.getAxiosHeaderObject();

      const axiosConfig: AxiosRequestConfig = {
        method: "GET",
        url: this.buildFinalServerURL(
          `/resources/${this.type}/items/${folderId}/path`
        ),
        headers: axiosHeader
      };

      axios
        .request(axiosConfig)
        .then((response: AxiosResponse) => {
          const { data } = response;
          const { result } = data;
          resolve(result);
        })
        .catch(error => {
          reject({
            type: RESOURCES_OPERATIONS_ERRORS.SERVER_ERROR,
            ...error
          } as ResourceError);
        })
        .finally(() => {
          this.__setFetchFolderResourcePathInProgress(false);
        });
    });
  }
}
