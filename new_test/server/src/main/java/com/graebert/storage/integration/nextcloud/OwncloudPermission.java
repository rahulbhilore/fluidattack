package com.graebert.storage.integration.nextcloud;

import com.github.sardine.DavResource;
import com.graebert.storage.util.Field;
import java.util.Map;

/**
 * The type Owncloud permission.
 */
public class OwncloudPermission {
  /**
   * The Permission string.
   */
  String permissionString = null;

  /**
   * Instantiates a new Owncloud permission.
   *
   * @param res the res
   */
  public OwncloudPermission(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey(Field.PERMISSIONS.getName())) {
      permissionString = props.get(Field.PERMISSIONS.getName());
    }
  }

  /**
   * Is shared boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean isShared() {
    return permissionString == null || permissionString.contains("S");
  }

  /**
   * Can share boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean canShare() {
    return permissionString == null || permissionString.contains("R");
  }

  /**
   * Is mounted boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean isMounted() {
    return permissionString == null || permissionString.contains("M");
  }

  /**
   * Can write boolean. For File
   *
   * @return the boolean
   */
  public boolean canWrite() {
    return permissionString == null || permissionString.contains("W");
  }

  /**
   * Can create boolean. For Folder
   *
   * @return the boolean
   */
  public boolean canCreate() {
    return canCreateFile() && canCreateFolder();
  }

  /**
   * Can create file boolean. For Folder
   *
   * @return the boolean
   */
  public boolean canCreateFile() {
    return permissionString == null || permissionString.contains("C");
  }

  /**
   * Can create folder boolean. For Folder
   *
   * @return the boolean
   */
  public boolean canCreateFolder() {
    return permissionString == null || permissionString.contains("K");
  }

  /**
   * Can delete boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean canDelete() {
    return permissionString == null || permissionString.contains("D");
  }

  /**
   * Can rename boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean canRename() {
    return permissionString == null || permissionString.contains("N");
  }

  /**
   * Can move boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean canMove() {
    return permissionString == null || permissionString.contains("V");
  }

  /**
   * Is owner boolean. For File or Folder
   *
   * @return the boolean
   */
  public boolean isOwner() {
    return permissionString == null || !permissionString.contains("S");
  }
}
