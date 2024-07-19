import React, { ReactElement } from "react";
import { StorageType } from "../../../../types/StorageTypes";
import { Collaborator, ObjectInfo, PermissionRole } from "./types";

type PermissionsDialogContextType = {
  isDrawing: boolean;
  isMobile: boolean;
  isInternalAccessAvailable: boolean;
  publicAccess: {
    createPublicLink: () => void;
    deletePublicLink: () => void;
    endTime: number;
    fileId: string;
    generatedLink: string | ReactElement;
    isDeleted: boolean;
    isExport: boolean;
    isExportAvailable: boolean;
    isPasswordRequired: boolean;
    isPublic: boolean;
    isPublicAccessAvailable: boolean;
    isPublicallyAccessible: boolean;
    isViewOnly: boolean;
    link: string | ReactElement;
    setGeneratedLink: (link: string) => void;
    updateObjectInfo: (shouldSpinnerBeShowed?: boolean) => void;
  };
  invitePeople: {
    availableRoles: PermissionRole[];
    isExpanded: boolean;
    setIsExpanded: (expanded: boolean) => void;
    collaborators: Array<
      Omit<Collaborator, "collaboratorRole"> & { role: PermissionRole }
    >;
    objectInfo: ObjectInfo;
    storage: StorageType | null;
    updateObjectInfo: (shouldSpinnerBeShowed?: boolean) => void;
  };
};

const PermissionsDialogContext =
  React.createContext<PermissionsDialogContextType>({
    isDrawing: false,
    isMobile: false,
    isInternalAccessAvailable: false,
    publicAccess: {
      createPublicLink: () => null,
      deletePublicLink: () => null,
      fileId: "",
      link: "",
      generatedLink: "",
      isViewOnly: true,
      isExport: false,
      isDeleted: false,
      isPasswordRequired: false,
      isPublic: false,
      isPublicallyAccessible: false,
      isPublicAccessAvailable: false,
      isExportAvailable: false,
      endTime: 0,
      setGeneratedLink: () => null,
      updateObjectInfo: () => null
    },
    invitePeople: {
      availableRoles: [],
      isExpanded: true,
      setIsExpanded: () => null,
      collaborators: [],
      objectInfo: {} as ObjectInfo,
      storage: null,
      updateObjectInfo: () => null
    }
  });

export { PermissionsDialogContext, PermissionsDialogContextType };
