type StorageType =
  | "box"
  | "internal"
  | "dropbox"
  | "gdrive"
  | "nextcloud"
  | "onedrive"
  | "onedrivebusiness"
  | "onshape"
  | "samples"
  | "sharepoint"
  | "trimble"
  | "webdav";

type StorageId<T extends StorageType> = `${T}_id`;
type StorageUsername<T extends StorageType> = `${T}_username`;

type StorageValues<ST extends StorageType> = {
  [key in StorageId<ST> | StorageUsername<ST> | "rootFolderId"]: string;
};

type UserStoragesInfo = {
  [ST in StorageType]: Array<
    ST extends "trimble"
      ? StorageValues<ST> & { regions: Array<string>; server: string }
      : StorageValues<ST>
  >;
};

type StorageSettings = {
  active: boolean;
  adminOnly: boolean;
  displayName: string;
  name: StorageType;
  profileOption: boolean;
};

export { StorageType, UserStoragesInfo, StorageValues, StorageSettings };
