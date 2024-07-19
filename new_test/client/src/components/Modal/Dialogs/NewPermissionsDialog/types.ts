type ObjectInfo = {
  id: string;
  permissions: {
    canManagePermissions: boolean;
    canViewPublicLink: boolean;
    canViewPermissions: boolean;
  };
  name: string;
  filename: string;
  mimeType: string;
  public: boolean;
  viewOnly: string | boolean;
  deleted: string | boolean;
  storage: string;
  link: string;
  isOwner: boolean;
  ownerEmail: string;
  ownerId: string;
  owner: string;
  share: {
    editor: Record<string, unknown>;
    viewer: Record<string, unknown>;
  };
  type: string;
  parent: string;
  publicLinkInfo: {
    expirationTime: number;
    export: boolean;
    externalId: string;
    fileId: string;
    isEnabled: true;
    link: string;
    passwordRequired: boolean;
    userId: string;
  };
};

type PermissionRole = "viewer" | "editor" | "owner" /* | "Custom" | */;

type PermittedUserType = {
  currentRole: PermissionRole;
  email: string;
  isInherited: boolean;
  name: string;
};

type Collaborator = {
  _id: string;
  canModify: boolean;
  email: string;
  isInherited: boolean;
  name: string;
  collaboratorRole: PermissionRole;
  userId: string;
  canDelete?: boolean;
};

export { Collaborator, ObjectInfo, PermissionRole, PermittedUserType };
