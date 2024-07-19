import { OWNER_TYPES } from "./BaseResourcesStore";
import ResourceFile from "./ResourceFile";
import ResourceFolder from "./ResourceFolder";

/**
 * Base interface for folder resources
 * @interface
 */
export interface ResourceFolderInterface {
  id: string | undefined;
  name: string | undefined;
  parent: string | undefined;
  type: string | undefined;
  created: number | undefined;
  updated: number | undefined;
  path: string | undefined;
  resourceType: string | undefined;
  description: string | undefined;
  ownerId: string | undefined;
  isOwner: boolean | undefined;
  ownerType: OWNER_TYPES | undefined;
  ownerName: string | undefined;
}

/**
 * Base interface for file resources
 * @interface
 */
export interface ResourceFileInterface extends ResourceFolderInterface {
  fileName: string | undefined;
  fileSize: string | undefined;
  fileType: string | undefined;
}

/**
 * Interface for path request
 */
export interface ResourcePathInterface {
  _id: string;
  viewOnly: boolean;
  name: string;
}

export interface IndexDbitemInterface
  extends ResourceFileInterface,
    ResourceFolderInterface {
  fullId: string;
  indexDbType: string;
}

export enum RESOURCES_OPERATIONS_ERRORS {
  FILE_NAME_DUPLICATED = "FILE_NAME_DUPLICATED",
  FOLDER_NAME_DUPLICATED = "FOLDER_NAME_DUPLICATED",
  INTERNAL_ERROR = "INTERNAL_ERROR",
  SERVER_ERROR = "SERVER_ERROR"
}

export interface ResourceError extends Error {
  type: RESOURCES_OPERATIONS_ERRORS;
  value?: string | string[];
}

/**
 * This interface is for compability with SmartTable
 */
export type SmartTableRowEntiry = {
  data: ResourceFile | ResourceFolder;
  fileSize: number;
  getter: () => unknown;
  isScrolling: boolean;
  tableId: string;
  _id: string;
};
