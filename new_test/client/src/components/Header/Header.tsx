import {
  AppBar,
  Grid,
  styled,
  Toolbar,
  useMediaQuery,
  useTheme
} from "@mui/material";
import React, {
  SyntheticEvent,
  useEffect,
  useMemo,
  useReducer,
  useState
} from "react";
import _ from "underscore";
import ApplicationActions from "../../actions/ApplicationActions";
import FilesListActions from "../../actions/FilesListActions";
import UserInfoActions from "../../actions/UserInfoActions";
import XenonConnectionActions from "../../actions/XenonConnectionActions";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationStore, {
  CONFIG_LOADED,
  UPDATE
} from "../../stores/ApplicationStore";
import FilesListStore, {
  CURRENT_FILE_DESHARED,
  CURRENT_FILE_INFO_UPDATED,
  DRAWING_RELOAD
} from "../../stores/FilesListStore";
import { StorageType, UserStoragesInfo } from "../../types/StorageTypes";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGE_REDIRECT_CHECK_READY,
  STORAGE_SWITCH
} from "../../stores/UserInfoStore";
import WebsocketStore, {
  CHANGE_EVENT,
  FILE_SOCKET
} from "../../stores/WebsocketStore";
import Logger from "../../utils/Logger";
import Storage from "../../utils/Storage";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import Logo from "./Logo/Logo";
import SpecificHeader from "./SpecificHeader/SpecificHeader";
import UserMenu from "./UserMenu/UserMenu";

const DSHeader = React.lazy(
  () => import(/* webpackChunkName: "DSHeader" */ "./DSHeader")
);

type GeneratePasswordHeaderFunc = ({
  realm,
  nonce,
  opaque,
  ha1,
  password
}: {
  realm: string;
  nonce: string;
  opaque: string;
  ha1: string;
  password?: string;
}) => string;

const StyledHeader = styled(AppBar)(({ theme }) => ({
  zIndex: theme.zIndex.drawer + 1,
  backgroundColor: theme.palette.SNOKE
}));

const StyledOtherHeader = styled(Grid)(() => ({
  width: "100%",
  height: "100%",
  overflowY: "hidden"
}));

const StyledToolbar = styled(Toolbar)(() => ({
  height: "50px",
  minHeight: "0 !important",
  padding: "0 !important"
}));

export default function Header() {
  const [isConfigLoaded, setIsConfigLoaded] = useState(false);
  const [fileWSId, setFileWSId] = useState<string | null>(null);
  const [notificationBadge, setNotificationBadge] = useState(0);
  const [fileDeleted, setFileDeleted] = useState(false);
  const theme = useTheme();
  const isSmallerThanMd = useMediaQuery(theme.breakpoints.down("md"));
  const isSmallerThanDrawingThreshold = useMediaQuery(
    `@media (max-width:${theme.kudoStyles.THRESHOLD_TO_DRAWING_PAGE}px)`
  );
  const isMobileDevice = MainFunctions.isMobileDevice();
  const isDrawingPage = location.pathname.indexOf(`/file/`) !== -1;
  const isMobile = useMemo(
    () =>
      isDrawingPage
        ? isSmallerThanDrawingThreshold
        : isMobileDevice || isSmallerThanMd,
    [
      isMobileDevice,
      isSmallerThanMd,
      isDrawingPage,
      isSmallerThanDrawingThreshold
    ]
  );

  const changePageType = (e: React.UIEvent) => {
    e.preventDefault();

    const handleRedirect = (url: string) => {
      const isInIframe = MainFunctions.isInIframe();
      if (isInIframe) {
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        ApplicationActions.changePage(url);
      }
      return true;
    };

    const redirectFunction = () => {
      const isInIframe = MainFunctions.isInIframe();
      UserInfoStore.removeChangeListener(
        STORAGE_REDIRECT_CHECK_READY,
        redirectFunction
      );
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      const storagesInfo = Object.values(UserInfoStore.getStoragesInfo());
      const areStoragesConnected =
        storagesInfo.length && _.flatten(storagesInfo).length;
      const { pathname } = location;
      // No storages connected - go to storages page
      if (!areStoragesConnected) {
        SnackbarUtils.alertInfo({ id: "connectToOneOfTheStorages" });
        if (!pathname.includes("storages")) {
          return handleRedirect(`${UIPrefix}storages`);
        }
        return true;
      }
      let objectId = "-1";
      if (pathname.includes("file") && !pathname.includes("files")) {
        // file's page - try to go to parent folder
        const { folderId = "", _id: fileId } = FilesListStore.getCurrentFile();
        const { wsId: folderWsId } = FilesListStore.getCurrentFolder();
        const {
          storageType,
          storageId,
          objectId: pureId
        } = MainFunctions.parseObjectId(folderId);
        objectId = pureId;

        // if we cannot determine storage from parent - let's try to find it from current file
        const { storageType: fileStorage, storageId: fileAccount } =
          MainFunctions.parseObjectId(fileId);
        // XENON-58125
        if (!isInIframe) {
          FilesListActions.resetCurrentFileInfo();
        }
        if (
          (storageType || fileStorage) &&
          (storageId || fileAccount) &&
          objectId
        ) {
          return handleRedirect(
            `${UIPrefix}files/${storageType || fileStorage}/${
              storageId || fileAccount
            }/${objectId || folderWsId}`
          );
        }
      } else if (pathname.includes("files/") && !pathname.includes("search")) {
        const { storage, accountId } = FilesListStore.getCurrentFolder();
        const filteredPathname = pathname
          .replace(`${UIPrefix}files/`, "")
          .replace("trash/", "");
        const [, storageCode, storageId] = filteredPathname.split("/");
        // DK: Not sure what should be priority here
        return handleRedirect(
          `${UIPrefix}files/${storage || storageCode}/${
            accountId || storageId
          }/-1`
        );
      }
      // redirect to "last active" storage
      const { type, id } = UserInfoStore.getUserInfo("storage");
      return handleRedirect(
        `${UIPrefix}files/${MainFunctions.serviceNameToStorageCode(
          type
        )}/${id}/${objectId}`
      );
    };
    if (Object.keys(UserInfoStore.getStoragesInfo()).length > 0) {
      redirectFunction();
    } else {
      UserInfoStore.addChangeListener(
        STORAGE_REDIRECT_CHECK_READY,
        redirectFunction
      );
      UserInfoActions.getUserStorages();
    }
  };

  const checkPasswordForPublicLink = (
    token: string,
    fileId: string
  ): Promise<string> =>
    new Promise(resolve => {
      const password =
        (Storage.store(btoa(`password_${fileId}_${token}`)) as string) || ""; // here password is ha1
      if (password.length > 0) {
        FilesListActions.getNonceForPublicLinkPassword()
          .then(({ nonce, realm, opaque }) => {
            const passwordHeader = (
              FilesListActions.generatePasswordHeader as GeneratePasswordHeaderFunc
            )({
              realm,
              nonce,
              opaque,
              ha1: password
            });
            resolve(passwordHeader);
          })
          .catch(() => {
            resolve("");
          });
      } else {
        resolve("");
      }
    });

  const onConfigLoaded = () => {
    setIsConfigLoaded(true);
  };

  const [, forceUpdate] = useReducer(x => x + 1, 0);

  const onChange = () => {
    setTimeout(() => forceUpdate(), 0);
  };

  const clearFileDeleted = () => {
    if (!fileDeleted) return;
    setFileDeleted(false);
  };

  const onFileWebSocketMessage = async (event: {
    id: string;
    socketType: string;
    type: string;
    author: string;
    xSessionId: string;
  }) => {
    const connection = WebsocketStore.getConnectionInfo(event.id);
    const currentFile = FilesListStore.getCurrentFile();
    if (connection) {
      const isPublicLink =
        (MainFunctions.QueryString("token").length as number) > 0;
      const isConnected = connection.isConnected === true;
      const lastMessage = connection.lastMessage
        ? connection.lastMessage.messageType
        : null;
      if (event.type === "newSocket" && event.socketType === FILE_SOCKET) {
        setFileWSId(event.id);
        if (!isPublicLink) {
          const currentFileId = currentFile?._id;
          if (currentFileId)
            FilesListActions.getObjectInfo(currentFileId, "file", {});
        }
      } else if (
        event.type === "connectionClosed" &&
        event.socketType === FILE_SOCKET
      ) {
        setFileWSId(null);
      } else if (
        event.socketType === FILE_SOCKET &&
        event.type === "commentsUpdate"
      ) {
        if (isPublicLink) {
          let showCommentsNotifications = false;

          if (currentFile.isOwner) showCommentsNotifications = true;

          const { storageType } = MainFunctions.parseObjectId(currentFile._id);

          const currentUserEmail = UserInfoStore.getUserInfo("email");

          if (
            currentFile.share &&
            !showCommentsNotifications &&
            storageType === "SF"
          ) {
            currentFile.share.editor.forEach((elem: { email: string }) => {
              if (elem.email === currentUserEmail)
                showCommentsNotifications = true;
            });

            if (!showCommentsNotifications)
              currentFile.share.viewer.forEach((elem: { email: string }) => {
                if (elem.email === currentUserEmail)
                  showCommentsNotifications = true;
              });
          } else if (currentFile.share && !showCommentsNotifications) {
            const storageName = MainFunctions.storageCodeToServiceName(
              storageType
            ) as StorageType;

            const connectedStorages = (
              UserInfoStore.getStoragesInfo() as UserStoragesInfo
            )[storageName];

            if (connectedStorages) {
              const connectedStoragesUserNames = connectedStorages.map(
                elem => elem[`${storageName}_username` as keyof typeof elem]
              );

              currentFile.share.editor.forEach(
                (elem: { email: string; [key: string]: string }) => {
                  if (
                    !showCommentsNotifications &&
                    connectedStoragesUserNames.indexOf(elem.email) > -1
                  )
                    showCommentsNotifications = true;
                }
              );

              if (!showCommentsNotifications)
                currentFile.share.viewer.forEach(
                  (elem: { email: string; [key: string]: string }) => {
                    if (
                      !showCommentsNotifications &&
                      connectedStoragesUserNames.indexOf(elem.email) > -1
                    )
                      showCommentsNotifications = true;
                  }
                );
            }
          }

          if (
            (UserInfoStore.getUserInfo("id") === event.author ||
              showCommentsNotifications) &&
            currentFile.drawingSessionId !== event.xSessionId
          ) {
            setNotificationBadge(prev => prev + 1);
          }
        } else if (currentFile.drawingSessionId !== event.xSessionId) {
          setNotificationBadge(prev => prev + 1);
        }
      } else if (event.type === "newVersion") {
        if (!isPublicLink) {
          const currentFileId = currentFile?._id;
          if (currentFileId)
            FilesListActions.getObjectInfo(currentFileId, "file", {});
        } else {
          const isLoggedIn = !!Storage.store("sessionId");
          const currentFileId = currentFile?._id;

          if (currentFileId && isLoggedIn) {
            // Opened by token, but as user can have direct access
            // let's check if it was viewOnly or not in the first place
            const token = MainFunctions.QueryString("token");
            const isViewOnly = currentFile.viewOnly;
            let password = "";
            let shouldCheckLinkInfo = isViewOnly;
            if (!isViewOnly) {
              try {
                await FilesListActions.getObjectInfo(currentFileId, "file", {});
              } catch (ex) {
                // if we catch ex - it means that file isn't available directly
                shouldCheckLinkInfo = true;
              }
            }
            if (shouldCheckLinkInfo) {
              password = await checkPasswordForPublicLink(
                token as string,
                currentFileId
              );
              FilesListActions.getObjectInfo(currentFileId, "file", {
                isExternal: false,
                token,
                password
              } as { isExternal: boolean; token: string; password: string });
            }
          }
        }
      }
      if (
        event.id === fileWSId &&
        isConnected === false &&
        lastMessage === "deleted"
      ) {
        setFileDeleted(true);
      }
    } else {
      Logger.addEntry("WARNING", "No WebSocket connection for file!");
    }
  };

  const onCommentButtonClick = (e: SyntheticEvent) => {
    e.preventDefault();
    // IA: this is ugly fix for XENON-52013,
    // it should be removed when new way of handling
    // sessions will be implemented
    FilesListStore.ignoreSettingModifiedFlagOneTime();

    // this.setState(
    //   { notificationBadge: 0 },
    //   XenonConnectionActions.postMessage({
    //     messageName: "comments"
    //   })
    // );

    // BD:how setState above should be implemented here
    setNotificationBadge(0);
    XenonConnectionActions.postMessage({
      messageName: "comments"
    });
  };

  useEffect(() => {
    FilesListStore.addChangeListener(onChange);
    FilesListStore.addEventListener(CURRENT_FILE_INFO_UPDATED, onChange);
    FilesListStore.addEventListener(CURRENT_FILE_DESHARED, onChange);
    FilesListStore.addEventListener(DRAWING_RELOAD, onChange);
    UserInfoStore.addChangeListener(INFO_UPDATE, onChange);
    UserInfoStore.addChangeListener(STORAGE_SWITCH, onChange);
    ApplicationStore.addChangeListener(CONFIG_LOADED, onConfigLoaded);
    ApplicationStore.addChangeListener(UPDATE, onChange);
    WebsocketStore.addEventListener(CHANGE_EVENT, onFileWebSocketMessage);
    return () => {
      FilesListStore.removeChangeListener(onChange);
      FilesListStore.removeEventListener(CURRENT_FILE_INFO_UPDATED, onChange);
      FilesListStore.removeEventListener(CURRENT_FILE_DESHARED, onChange);
      FilesListStore.removeEventListener(DRAWING_RELOAD, onChange);
      ApplicationStore.removeChangeListener(CONFIG_LOADED, onConfigLoaded);
      ApplicationStore.removeChangeListener(UPDATE, onChange);
      UserInfoStore.removeChangeListener(INFO_UPDATE, onChange);
      UserInfoStore.removeChangeListener(STORAGE_SWITCH, onChange);
      WebsocketStore.removeEventListener(CHANGE_EVENT, onFileWebSocketMessage);
    };
  }, []);

  const isCommander =
    location.pathname.indexOf("commander") > -1 ||
    window.navigator.userAgent.indexOf("ARES Commander") > -1;
  const currentPage = MainFunctions.detectPageType();
  const isSlimVersion =
    MainFunctions.QueryString("slim") === "true" && currentPage === "file";
  if (isSlimVersion || isCommander || currentPage === "notify") return null;
  const isLoggedIn = !!Storage.store("sessionId");
  const currentFile = FilesListStore.getCurrentFile();

  if (!isConfigLoaded) return null;

  if (ApplicationStore.getApplicationSetting("product") === "DraftSight")
    return <DSHeader changePageType={changePageType} />;

  return (
    <StyledHeader position="fixed" elevation={0} data-header>
      <StyledToolbar data-toolbar>
        <Logo
          changePageType={changePageType}
          isLoggedIn={isLoggedIn}
          isMobile={isMobile}
          currentFile={currentFile}
          onCommentButtonClick={onCommentButtonClick}
          notificationBadge={notificationBadge}
        />
        <StyledOtherHeader container justifyContent="space-between">
          <SpecificHeader
            fileDeleted={fileDeleted}
            notificationBadge={notificationBadge}
            onCommentButtonClick={onCommentButtonClick}
            isMobile={isMobile}
            clearFileDeleted={clearFileDeleted}
          />
          <UserMenu fileViewFlag={currentFile.viewFlag} isMobile={isMobile} />
        </StyledOtherHeader>
      </StyledToolbar>
    </StyledHeader>
  );
}
