import { makeObservable, observable, action, autorun } from "mobx";
import BaseResourcesStore from "./BaseResourcesStore";

/**
 * Interface for uploadable files
 * @interface
 */
export interface QueueUploadableInterface {
  id: string | undefined;

  inputFile: File | undefined;

  isUploaded: boolean | undefined;

  isUploading: boolean | undefined;

  uploadProgress: number | undefined;

  uploadPriority: number | undefined;

  startUpload: () => void;

  setUploadPriority: (priority: number) => void;

  getFormData: () => FormData;

  uploadPromise: Promise<unknown> | undefined;

  uploadPromiseResolver: (value?: unknown) => void;

  uploadPromiseRejecter: (reason?: unknown) => void;
}

export const NO_PRIORITY = 0;
export const NO_UPLOAD_LIMITS = 0;
const MAX_NUMBER_OF_UPLOADS = 5;

/**
 * Queue loader class
 * @class
 */
export default class QueueLoader {
  uploadQueue: QueueUploadableInterface[] = [];

  maxNumberOfUploads: number = MAX_NUMBER_OF_UPLOADS;

  storage: BaseResourcesStore | undefined = undefined;

  constructor(storage: BaseResourcesStore) {
    makeObservable(this, {
      uploadQueue: observable,
      maxNumberOfUploads: observable,
      setMaxNumberOfUploads: action,
      addEntityToQueue: action
    });

    this.storage = storage;
    this.run();
  }

  protected run() {
    autorun(() => {
      this.loadProcess();
    });
  }

  /**
   * Iteration of upload queue
   * @returns void
   */
  protected loadProcess() {
    if (!this.uploadQueue.length) return;

    if (this.maxNumberOfUploads !== NO_UPLOAD_LIMITS) {
      const nowUploading = this.uploadQueue.filter(item => item.isUploading);

      if (nowUploading.length >= this.maxNumberOfUploads) return;
    }

    const canBeUploaded = this.uploadQueue
      .filter(item => !item.isUploaded && !item.isUploading)
      .sort((a, b) => {
        if (a.uploadPriority === undefined || b.uploadPriority === undefined)
          return 0;
        return a.uploadPriority - b.uploadPriority;
      });

    if (this.maxNumberOfUploads !== NO_UPLOAD_LIMITS) {
      canBeUploaded.splice(this.maxNumberOfUploads);
    }

    canBeUploaded.forEach(item => {
      item.startUpload();
    });
  }

  // public stopAllUploads() {}

  /**
   * Add file to upload queue
   * @param entity
   */
  public addEntityToQueue(entity: QueueUploadableInterface) {
    this.uploadQueue.push(entity);
  }

  /**
   * Remove entity from upload queue
   * @param entity
   */
  public removeEntityFromQueue(entity: QueueUploadableInterface) {
    // TODO: if already uploading, stop it too
    this.uploadQueue = this.uploadQueue.filter(item => item.id !== entity.id);
  }

  /**
   * Set max number of uploads
   * @param maxNumber
   */
  public setMaxNumberOfUploads(maxNumber: number) {
    this.maxNumberOfUploads = maxNumber;
  }
}
