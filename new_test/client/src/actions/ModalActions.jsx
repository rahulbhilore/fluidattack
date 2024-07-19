import React from "react";
import { FormattedMessage } from "react-intl";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ModalConstants from "../constants/ModalConstants";
import * as IntlTagValues from "../constants/appConstants/IntlTagValues";
import MainFunctions from "../libraries/MainFunctions";

const getTranslatedCaption = str => <FormattedMessage id={str} />;

export default class ModalActions {
  static createFolder(caption) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CREATE_FOLDER,
      caption: getTranslatedCaption(caption || "createFolder")
    });
  }

  static createFile() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CREATE_FILE,
      caption: getTranslatedCaption("createDrawing")
    });
  }

  static resetSettings() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.RESET_SETTINGS,
      caption: getTranslatedCaption("reset")
    });
  }

  static confirmEraseFiles() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONFIRM_ERASE_FILES,
      caption: getTranslatedCaption("eraseFiles")
    });
  }

  static removeShare(id, name, type) {
    const shrinkedName = MainFunctions.shrinkNameWithExtension(
      name || "Unknown"
    );
    const enforceTooltip =
      name?.length > 0 && shrinkedName.length !== name.length;
    AppDispatcher.dispatch({
      actionType: ModalConstants.REMOVE_PERMISSION,
      caption: (
        <FormattedMessage
          id="removePermissionFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: shrinkedName,
            objectType: type
          }}
        />
      ),
      id,
      type,
      name,
      enforceTooltip,
      fullCaption: enforceTooltip ? (
        <FormattedMessage
          id="removePermissionFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: name || "Unknown",
            objectType: type
          }}
        />
      ) : null
    });
  }

  static shareManagement(id, knownName, objectType) {
    let type = "file";
    let name = null;
    if (objectType) {
      type = objectType;
    }
    if (knownName) {
      name = knownName;
    }
    if (!knownName || !objectType) {
      // eslint-disable-next-line no-console
      console.error("shareManagement - Unknown name or type!");
    }
    const shrinkedName = MainFunctions.shrinkNameWithExtension(
      name || "Unknown"
    );
    const enforceTooltip =
      name?.length > 0 && shrinkedName.length !== name.length;
    AppDispatcher.dispatch({
      actionType: ModalConstants.PERMISSIONS,
      caption: (
        <FormattedMessage
          id="permissionsFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: shrinkedName,
            objectType: type
          }}
        />
      ),
      id,
      type,
      name,
      enforceTooltip,
      fullCaption: enforceTooltip ? (
        <FormattedMessage
          id="permissionsFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: name || "Unknown",
            objectType: type
          }}
        />
      ) : null
    });
  }

  static versionControl(fileId, folderId, fileName) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.VERSION_CONTROL,
      caption: (
        <FormattedMessage
          id="manageVersionsFor"
          values={{ strong: IntlTagValues.strong, file: fileName }}
        />
      ),
      fileId,
      folderId,
      fileName
    });
  }

  static upgradeFileSession(fileId, fileDeleted) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.UPGRADE_FILE_SESSION,
      caption: getTranslatedCaption("switchToEditMode"),
      fileId,
      fileDeleted
    });
  }

  static changeEditor(id) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CHANGE_EDITOR,
      caption: getTranslatedCaption("changeEditor"),
      id
    });
  }

  static restoreDuplicates(duplicates) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.RESTORE_DUPLICATES,
      caption: getTranslatedCaption("restore"),
      duplicates
    });
  }

  static forgotPassword() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.FORGOT_PASSWORD,
      caption: getTranslatedCaption("forgotPassword")
    });
  }

  static deleteObjects(type, list, templateType, data) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.DELETE_OBJECTS,
      caption: getTranslatedCaption("delete"),
      type,
      list,
      templateType,
      data
    });
  }

  static deleteObjectConfirmation(entities, ids) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONFIRM_DELETE,
      caption: getTranslatedCaption("delete"),
      entities,
      ids
    });
  }

  static confirmRemoval(entities, type) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONFIRM_REMOVAL,
      caption: getTranslatedCaption("delete"),
      entities,
      type
    });
  }

  static eraseObjects(objects) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.ERASE_OBJECTS,
      caption: getTranslatedCaption("erase"),
      objects
    });
  }

  static moveObjects(tableSelection) {
    let name = <FormattedMessage id="entities" />;
    if (tableSelection.length === 1) {
      name = tableSelection[0].name;
    }
    AppDispatcher.dispatch({
      actionType: ModalConstants.MOVE_OBJECTS,
      caption: (
        <FormattedMessage
          id="moveTo"
          values={{
            strong: IntlTagValues.strong,
            name
          }}
        />
      )
    });
  }

  static showImage(fileName, id) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.SHOW_IMAGE,
      caption: fileName,
      id
    });
  }

  static uploadTemplate(type) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.UPLOAD_TEMPLATE,
      caption: (
        <FormattedMessage
          id="uploadObject"
          values={{ type: <FormattedMessage id="templates" /> }}
        />
      ),
      type
    });
  }

  static delinkStorage(storage, id) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.DELINK_STORAGE,
      caption: getTranslatedCaption("delinkStorage"),
      storage,
      id
    });
  }

  static changeUserOptions(userInfo) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CHANGE_USER_OPTIONS,
      caption: getTranslatedCaption("changeOptions"),
      userInfo
    });
  }

  static changeThumbnailsOptions(userInfo) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CHANGE_THUMBNAILS_OPTIONS,
      caption: getTranslatedCaption("changeThumbnailsOptions"),
      userInfo
    });
  }

  static connectionLost() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONNECTION_LOST,
      caption: getTranslatedCaption("connectionLost")
    });
  }

  static drawingUpdated() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.DRAWING_UPDATED,
      caption: getTranslatedCaption("drawingHasChanged")
    });
  }

  static exportStateUpdated(exportStateUpdate) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.EXPORTSTATE_UPDATED,
      caption: getTranslatedCaption("exportStateUpdate"),
      exportStateUpdate
    });
  }

  static connectStorageRequest(storageType) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONNECT_STORAGE_REQUEST,
      caption: getTranslatedCaption("connectStorage"),
      storageType
    });
  }

  static showChooseDialog(commandType, attachmentType, filter, wtObjectId) {
    let caption = "chooseDrawing";
    if (attachmentType === "image") {
      caption = "chooseImage";
    } else if (attachmentType === "dgn") {
      caption = "chooseDgn";
    } else if (attachmentType === "linetype") {
      caption = "chooseLinetypes";
    } else if (attachmentType === "cfg") {
      caption = "chooseCFGorDWG";
    } else if (attachmentType === "richLine") {
      caption = "chooseRichLineStyle";
    } else if (attachmentType === "pdf") {
      caption = "choosePDF";
    } else if (attachmentType === "las") {
      caption = "chooseLAS";
    }
    AppDispatcher.dispatch({
      actionType: ModalConstants.CHOOSE_OBJECT,
      caption: getTranslatedCaption(caption),
      commandType,
      attachmentType,
      filter,
      wtObjectId
    });
  }

  static hide() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.HIDE
    });
  }

  static hideByType(type) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.HIDE,
      type
    });
  }

  static drawingSessionExpired() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.FILE_SESSION_EXPIRED,
      caption: getTranslatedCaption("drawingSessionExpired")
    });
  }

  static downgradeDrawingSession() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.DOWNGRADE_FILE_SESSION,
      caption: getTranslatedCaption("drawingSessionDowngraded")
    });
  }

  static editPermissionSwitched(wasFileModified) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.EDIT_PERMISSION_SWITCHED,
      caption: getTranslatedCaption("drawingSessionDowngraded"),
      wasFileModified
    });
  }

  static connectWebDav() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.WEBDAV_CONNECT,
      caption: getTranslatedCaption("webDAVConnect")
    });
  }

  static connectNextCloud() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.NEXTCLOUD_CONNECT,
      caption: getTranslatedCaption("nextCloudConnect")
    });
  }

  static showMessageInfo(msgdata) {
    let caption = "message";
    if (msgdata.msgId === "inactiveUser") {
      caption = "inactiveUser";
    }

    AppDispatcher.dispatch({
      actionType: ModalConstants.SHOW_MESSAGE_INFO,
      caption: getTranslatedCaption(caption),
      msgId: msgdata.msgId,
      message: msgdata.message,
      onSubmit: msgdata.onSubmit || null,
      onClose: msgdata.onClose || null
    });
  }

  static removePublicLink(fileId, name) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.REMOVE_PUBLIC_LINK_CONFIRMATION,
      caption: getTranslatedCaption("sharingLinkDeletion"),
      fileId,
      name
    });
  }

  static deleteUser(userId, deleteId) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.DELETE_USER,
      caption: getTranslatedCaption("deleteUser"),
      userId,
      deleteId
    });
  }

  static requestUIReload(onAccept) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.RELOAD_REQUEST,
      caption: getTranslatedCaption("newVersion"),
      onAccept
    });
  }

  static sendFeedback() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.SEND_FEEDBACK,
      caption: getTranslatedCaption("feedback")
    });
  }

  static confirmWaitOrReload(
    onAction,
    onClose,
    onViewOnly,
    allowForce = false
  ) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONFIRM_WAIT_OR_RELOAD,
      caption: getTranslatedCaption("message"),
      onAction,
      onClose,
      onViewOnly,
      allowForce
    });
  }

  static saveAs(fileId, filterArray, saveFilterIndex, commandName, caption) {
    let processedCaption = caption;

    if (!caption) {
      processedCaption = getTranslatedCaption("saveAs");
    }

    AppDispatcher.dispatch({
      actionType: ModalConstants.SAVE_AS,
      caption: processedCaption,
      fileId,
      filterArray,
      saveFilterIndex,
      commandName
    });
  }

  static saveVersionAs(fileId, versionId, folderId, fileName) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.SAVE_VERSION_AS,
      caption: getTranslatedCaption("saveVersionAs"),
      fileId,
      folderId,
      versionId,
      fileName
    });
  }

  static chooseFolder() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.FOLDER_CHOOSER,
      caption: getTranslatedCaption("chooseFolder")
    });
  }

  static exportFile(fileId, filterArray, saveFilterIndex, messageName) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.EXPORT_FILE,
      caption: getTranslatedCaption("exportFile"),
      fileId,
      filterArray,
      saveFilterIndex,
      messageName
    });
  }

  static emitKeyEvent(action, event) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.KEY_EVENT,
      action,
      event
    });
  }

  static passwordRequiredForLink(fileId, token) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.LINK_PASSWORD_REQUIRED,
      caption: getTranslatedCaption("password"),
      fileId,
      token
    });
  }

  static openGdriveTempFile(accountId, fileId) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.OPEN_GDRIVE_FILE_TEMP,
      caption: getTranslatedCaption("opening"),
      fileId,
      accountId
    });
  }

  static promptToConnectAccount(accountId, fileId) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.GDRIVE_CONNECT_ACCOUNT,
      caption: getTranslatedCaption("opening"),
      fileId,
      accountId
    });
  }

  static openInCommander(needSave = false) {
    const mobile = MainFunctions.isMobileDevice();

    AppDispatcher.dispatch({
      actionType: ModalConstants.OPEN_IN_COMMANDER,
      caption: getTranslatedCaption(mobile ? "openInTouch" : "openInCommander"),
      needSave
    });
  }

  static compareDrawings(file, fileToCompare) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.COMPARE_DRAWINGS,
      caption: getTranslatedCaption("compare"),
      file,
      fileToCompare
    });
  }

  static showAbout() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.SHOW_ABOUT,
      caption: getTranslatedCaption("about")
    });
  }

  static renameEntity(entityId, name, type) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.RENAME_ENTITY,
      caption: getTranslatedCaption("rename"),
      entityId,
      name,
      type
    });
  }

  static createBlockLibrary() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CREATE_BLOCK_LIBRARY,
      caption: getTranslatedCaption("createNewBlockLibrary")
    });
  }

  static createResourceFolder(storage, ownerType) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CREATE_RESOURCE_FOLDER,
      caption: getTranslatedCaption("createFolder"),
      storage,
      ownerType
    });
  }

  static uploadBlock(libraryId) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.UPLOAD_BLOCK,
      caption: getTranslatedCaption("uploadNewBlock"),
      libraryId
    });
  }

  static updateBlockLibrary(libraryId, name, description) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.UPDATE_BLOCK_LIBRARY,
      caption: getTranslatedCaption("updateBlockLibrary"),
      libraryId,
      name,
      description
    });
  }

  static updateBlock(blockId, libraryId, name, description, fileName) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.UPDATE_BLOCK,
      caption: getTranslatedCaption("updateBlock"),
      blockId,
      libraryId,
      name,
      description,
      fileName
    });
  }

  static chooseBlockLibrary() {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CHOOSE_BLOCK_LIBRARY,
      caption: getTranslatedCaption("chooseBlockLibrary")
    });
  }

  static shareBlock(id, libraryId, type, name) {
    const shrinkedName = MainFunctions.shrinkNameWithExtension(
      name || "Unknown"
    );
    const enforceTooltip =
      name?.length > 0 && shrinkedName.length !== name.length;
    AppDispatcher.dispatch({
      actionType: ModalConstants.SHARE_BLOCK,
      caption: (
        <FormattedMessage
          id="permissionsFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: shrinkedName,
            objectType: type
          }}
        />
      ),
      enforceTooltip,
      fullCaption: enforceTooltip ? (
        <FormattedMessage
          id="permissionsFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: name || "Unknown",
            objectType: type
          }}
        />
      ) : null,
      id,
      libraryId,
      type,
      name
    });
  }

  static openConflictingFile(fileName, conflictingFileId, conflictingFileName) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CONFLICTING_FILE_CREATED,
      caption: getTranslatedCaption("conflictingFile"),
      fileName,
      conflictingFileId,
      conflictingFileName
    });
  }

  static cloneObject(fileId, type, objectName, isShortcut = false) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CLONE_OBJECT,
      caption: getTranslatedCaption("cloneObject"),
      fileId,
      type,
      objectName,
      isShortcut
    });
  }

  static createShortcut(fileId, type, objectName) {
    AppDispatcher.dispatch({
      actionType: ModalConstants.CREATE_SHORTCUT,
      caption: getTranslatedCaption("createShortcut"),
      fileId,
      type,
      objectName
    });
  }
}
