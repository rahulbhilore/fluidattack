import { SortDirection, SortDirectionType } from "react-virtualized";
import Processes from "../constants/appConstants/Processes";
import ProcessStore from "../stores/ProcessStore";

export type FileObject = {
  id: string;
  name: string;
  storage?: string;
  externalType?: string;
  isShortcut?: boolean;
  type: string;
  updateDate?: number;
  creationDate?: number;
  isOwner: boolean;
  viewOnly: boolean;
  sizeValue: number;
  shortcutInfo?: {
    type: string;
  };
};

const TOP_PROCESS_TYPES = [
  Processes.CREATING,
  Processes.CREATE_COMPLETED,
  Processes.UPLOAD,
  Processes.PREPARING,
  Processes.UPLOADING,
  Processes.FINISHING_UPLOAD,
  Processes.UPLOAD_CANCELED,
  Processes.UPLOAD_COMPLETE
];

export const sortByType = (a: FileObject, b: FileObject) => {
  // for OS the order is folder -> document -> file
  if (a.storage && b.storage && a.storage === "OS" && b.storage === "OS") {
    const aType = a.externalType;
    const bType = b.externalType;
    if (aType === "folder" && bType !== "folder") return -1;
    if (bType === "folder" && aType !== "folder") return 1;
    if (aType === "document" && bType === "file") return -1;
    if (bType === "document" && aType === "file") return 1;
  }

  const aType = a.isShortcut ? a.shortcutInfo?.type : a.type;
  const bType = b.isShortcut ? b.shortcutInfo?.type : b.type;

  if (aType === "folder" && bType !== "folder") return -1;
  if (bType === "folder" && aType !== "folder") return 1;
  return 0;
};

export const sortByProcess = (
  a: FileObject,
  b: FileObject,
  sortDirection: SortDirectionType
) => {
  if (ProcessStore.getProcessesSize() > 0) {
    const runningProcessesA = ProcessStore.getProcess(a.id);
    const runningProcessesB = ProcessStore.getProcess(b.id);
    if (!runningProcessesA && !runningProcessesB) return 0;
    const isAProcessTop = TOP_PROCESS_TYPES.includes(runningProcessesA?.type);
    const isBProcessTop = TOP_PROCESS_TYPES.includes(runningProcessesB?.type);
    if (isAProcessTop && !isBProcessTop)
      return sortDirection === SortDirection.ASC ? -1 : 1;
    if (isBProcessTop && !isAProcessTop)
      return sortDirection === SortDirection.ASC ? 1 : -1;
  }
  return 0;
};

const SPECIAL_CHARACTERS_REGEXP = /^[~!@#$%^&?*()_+=\\\-/'.<>]+/;
const NUMBERS_REGEXP = /^[0-9]+/;

export const sortByName = (
  a: FileObject,
  b: FileObject,
  sortDirection: SortDirectionType
) => {
  const processSort = sortByProcess(a, b, sortDirection);
  if (processSort !== 0) return processSort;
  const typeSort = sortByType(a, b);
  if (typeSort === 0) {
    const aName = a.name;
    const bName = b.name;
    const aSpecialCharactersStart = SPECIAL_CHARACTERS_REGEXP.test(aName);
    const bSpecialCharactersStart = SPECIAL_CHARACTERS_REGEXP.test(bName);
    if (aSpecialCharactersStart !== bSpecialCharactersStart) {
      return aSpecialCharactersStart ? -1 : 1;
    }
    const aNumberStart = NUMBERS_REGEXP.test(aName);
    const bNumberStart = NUMBERS_REGEXP.test(bName);
    if (aNumberStart !== bNumberStart) {
      return aNumberStart ? -1 : 1;
    }
    // don't need modifier here
    return a.name.localeCompare(b.name);
  }
  const modifier = sortDirection === SortDirection.ASC ? 1 : -1;
  return modifier * typeSort;
};

const calculateAccessScore = (f: FileObject) => {
  if (f.isOwner) {
    return 5;
  }
  if (!f.viewOnly) {
    return 1;
  }
  return 0;
};

export const sortByAccess = (
  a: FileObject,
  b: FileObject,
  sortDirection: SortDirectionType
) => {
  const processSort = sortByProcess(a, b, sortDirection);
  if (processSort !== 0) return processSort;
  const typeSort = sortByType(a, b);
  const modifier = sortDirection === SortDirection.ASC ? 1 : -1;
  if (typeSort === 0) {
    const aAccess = calculateAccessScore(a);
    const bAccess = calculateAccessScore(b);
    if (aAccess === bAccess) return sortByName(a, b, sortDirection);
    return aAccess > bAccess ? 1 : -1;
  }
  return modifier * typeSort;
};

export const sortByModification = (
  a: FileObject,
  b: FileObject,
  sortDirection: SortDirectionType
) => {
  const processSort = sortByProcess(a, b, sortDirection);
  if (processSort !== 0) return processSort;
  const typeSort = sortByType(a, b);
  const modifier = sortDirection === SortDirection.ASC ? 1 : -1;
  if (typeSort === 0) {
    const aTime = a.updateDate || a.creationDate || 0;
    const bTime = b.updateDate || b.creationDate || 0;
    if (aTime === bTime) return sortByName(a, b, sortDirection);
    return aTime > bTime ? 1 : -1;
  }
  return modifier * typeSort;
};

export const sortBySize = (
  a: FileObject,
  b: FileObject,
  sortDirection: SortDirectionType
) => {
  const processSort = sortByProcess(a, b, sortDirection);
  if (processSort !== 0) return processSort;
  const typeSort = sortByType(a, b);
  const modifier = sortDirection === SortDirection.ASC ? 1 : -1;
  if (typeSort === 0) {
    const aSize = a.sizeValue || 0;
    const bSize = b.sizeValue || 0;
    if (aSize !== bSize) {
      return aSize > bSize ? 1 : -1;
    }
    return sortByName(a, b, sortDirection);
  }
  return modifier * typeSort;
};
