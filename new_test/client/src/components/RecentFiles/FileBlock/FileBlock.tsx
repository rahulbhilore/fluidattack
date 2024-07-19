import React, { useState, useEffect, useCallback } from "react";
import { FormattedMessage } from "react-intl";
import { styled, IconButton, Tooltip } from "@mui/material";
import $ from "jquery";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import Storage from "../../../utils/Storage";
import UserInfoStore from "../../../stores/UserInfoStore";
import ContextMenuActions from "../../../actions/ContextMenuActions";
import { iOSLongTapTime } from "../../../constants/appConstants/AppTimingConstants";
import FileLink from "./FileLink";
import OtherFileButton from "./OtherFileButton";
import FileInfo from "./FileInfo";
import ApplicationStore from "../../../stores/ApplicationStore";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import Thumbnail from "../../Thumbnail";
import closeSVG from "../../../assets/images/Close.svg";

export const THUMBNAIL_WIDTH = 165;
export const THUMBNAIL_HEIGHT = 88;

const StyledDivContainer = styled("div")(({ theme }) => ({
  display: "inline-block",
  margin: "0 30px 0 0",
  "& img.thumbnail": {
    border: `solid 1px ${theme.palette.REY}`,
    borderRadius: 0,
    padding: 0,
    margin: "0 auto",
    cursor: "pointer",
    pointerEvents: "auto",
    display: "block"
  },
  "@media (max-width: 767px)": {
    margin: "0 15px 0 0"
  },
  "&:hover .removeRecentIcon": {
    visibility: "visible"
  }
}));

const StyledSpan = styled("span")(() => ({
  position: "absolute",
  top: 4,
  right: 5
}));

const StyledIconButton = styled(IconButton)(() => ({
  backgroundColor: "#e7e5e5 !important",
  padding: 3,
  cursor: "pointer",
  display: "flex",
  justifyContent: "center",
  alignItems: "center",
  borderRadius: 0,
  visibility: "hidden"
}));

type Props = {
  id: string;
  name: string;
  thumbnail: string;
  folderId: string;
  storage: string;
  date: number;
  isVisible: boolean;
};

type TouchEventInfoType = {
  target: EventTarget;
  x: number;
  y: number;
};

export default function FileBlock({
  id,
  name,
  thumbnail,
  storage,
  folderId,
  date,
  isVisible
}: Props) {
  const [downloadStarted, setDownloadStarted] = useState(false);
  const [isWidthCalculated, setIsWidthCalculated] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const fileType =
    UserInfoStore.findApp(MainFunctions.getExtensionFromName(name)) || "xenon";
  const [fileLink, setFileLink] = useState(
    fileType === "xenon" ? `${location.origin}/file/${id}` : ""
  );
  const [touchStartTime, setTouchStartTime] = useState<number | null>(null);
  const [touchEventInfo, setTouchEventInfo] =
    useState<TouchEventInfoType | null>(null);

  const generateFileLink = (isReturn?: boolean, isOpen?: boolean) =>
    new Promise(resolve => {
      if (fileType === "pdf") {
        const oReq = new XMLHttpRequest();
        oReq.open(
          "GET",
          `${ApplicationStore.getApplicationSetting(
            "apiURL"
          )}/files/${id}/data`,
          true
        );
        const sessionId = Storage.store("sessionId");
        if (typeof sessionId === "string") {
          oReq.setRequestHeader("sessionId", sessionId);
        }
        if (isOpen === true) {
          oReq.setRequestHeader("open", "true");
        }
        oReq.responseType = "arraybuffer";
        oReq.onload = () => {
          if (oReq.status === 200) {
            const link = URL.createObjectURL(
              new Blob([oReq.response], { type: "application/pdf" })
            );
            if (isReturn === true) {
              resolve(link);
            } else {
              setFileLink(link);
            }
          }
        };
        oReq.send();
      } else {
        resolve("");
      }
    });

  useEffect(() => {
    if (fileType !== "xenon" && fileLink.length === 0) {
      generateFileLink();
    }
  }, []);

  const onDownloadStart = useCallback(() => {
    setDownloadStarted(true);
  }, []);

  const onDownloadEnd = useCallback(() => {
    setDownloadStarted(false);
  }, []);

  const onTouchStart = (event: React.TouchEvent<HTMLDivElement>) => {
    const newTouchEventInfo = {
      target: event.target,
      x: event.touches[0].pageX,
      y: event.touches[0].pageY
    };
    const newTouchStartTime = new Date().getTime();
    setTouchStartTime(newTouchStartTime);
    setTouchEventInfo(newTouchEventInfo);
  };

  /**
   * Showing context menu
   * @param e { Event | { target:Node, x:Number, y:Number } }
   */
  const showMenu = (
    event:
      | Event
      | React.MouseEvent<HTMLDivElement, MouseEvent>
      | TouchEventInfoType,
    isTouchEvent: boolean
  ) => {
    const e = event as Event;
    if (MainFunctions.isSelection(e)) return;
    if (touchStartTime !== null) {
      setTouchStartTime(null);
    }
    let x = 0;
    let y = 0;
    let target = null;
    if (isTouchEvent) {
      const touchEvent = event as TouchEventInfoType;
      // shift for (+10, +10) to prevent "auto-clicking" on first option in menu
      x = touchEvent.x + 10;
      y = touchEvent.y + 10;
      ({ target } = touchEvent);
    } else {
      const evt =
        (event as React.MouseEvent<HTMLDivElement, MouseEvent>) || Event;
      evt.preventDefault();
      ({ target } = evt);
      x = evt.pageX;
      y = evt.pageY;
    }
    if (!$(target).is("input")) {
      const { storageId, storageType, objectId } =
        MainFunctions.parseObjectId(folderId);
      ContextMenuActions.showMenu(
        x,
        y,
        id,
        {
          id,
          storageType: MainFunctions.storageCodeToServiceName(storageType),
          storageId,
          name,
          folderId: objectId,
          onDownloadStart,
          onDownloadEnd,
          type: "recent"
        },
        "recent"
      );
    }
  };

  const onTouchEnd = (event: React.TouchEvent<HTMLDivElement>) => {
    const touchStopTime = new Date().getTime();
    const releaseXPosition = event.changedTouches[0].pageX;
    const releaseYPosition = event.changedTouches[0].pageY;
    if (
      touchStartTime !== null &&
      touchEventInfo !== null &&
      touchStopTime - touchStartTime > iOSLongTapTime &&
      Math.abs(releaseXPosition - touchEventInfo.x) < 10 &&
      Math.abs(releaseYPosition - touchEventInfo.y) < 10
    ) {
      showMenu(touchEventInfo, true);
    } else {
      setTouchStartTime(null);
      setTouchEventInfo(null);
    }
  };

  const fileLinkWidthCalculated = () => {
    setIsWidthCalculated(true);
  };

  const restoreRecentFile = () => {
    FilesListActions.restoreRecentFile();
  };

  const removeRecentFile = () => {
    setIsProcessing(true);
    // remove file preview from the recents list
    FilesListActions.removeRecentPreview(id);

    // remove recent file item from DB
    FilesListActions.removeRecentFile(thumbnail, id, folderId, name, date)
      .then(() => {
        SnackbarUtils.undoLastAction("restoreRecentFile", restoreRecentFile);
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
      })
      .finally(() => {
        setIsProcessing(false);
      });
  };

  const openFile = () => {
    const { storageType, storageId } = MainFunctions.parseObjectId(id);

    // set for fade while info is loading
    setDownloadStarted(true);

    FilesListActions.getObjectInfo(id, "file", {})
      .then(() => {
        setDownloadStarted(false);
        if (fileType === "pdf") {
          generateFileLink(true, true).then((link: string) => {
            window.open(link, "_blank", "noopener,noreferrer");
          });
        } else {
          FilesListStore.open(
            id,
            MainFunctions.storageCodeToServiceName(storageType),
            storageId,
            true,
            null,
            `file/${id}`
          );
        }
      })
      .catch(err => {
        setDownloadStarted(false);
        SnackbarUtils.alertError(err.text);
        FilesListActions.loadRecentFiles();
      });
  };
  const storageName = storage.toLowerCase();

  return (
    <StyledDivContainer
      onContextMenu={event => {
        showMenu(event, false);
      }}
      onTouchStart={event => {
        onTouchStart(event);
      }}
      onTouchEnd={event => {
        onTouchEnd(event);
      }}
      style={{
        visibility: isWidthCalculated ? "initial" : "hidden",
        display: isVisible ? "inline-block" : " none",
        opacity: downloadStarted ? 0.6 : 1,
        position: "relative"
      }}
      data-component="recentFileBlock"
      data-name={name}
    >
      {fileType === "xenon" ? (
        <Thumbnail
          src={thumbnail}
          name={name}
          fileId={id}
          width={THUMBNAIL_WIDTH}
          height={THUMBNAIL_HEIGHT}
          dataComponent="recentFileThumbnail"
          className="thumbnail"
          onClick={e => {
            e.preventDefault();
            openFile();
          }}
        />
      ) : (
        <OtherFileButton
          name={name}
          dataComponent="recentFileThumbnail"
          onClick={openFile}
          width={THUMBNAIL_WIDTH}
          height={THUMBNAIL_HEIGHT}
        />
      )}
      <Tooltip placement="top" title={<FormattedMessage id="remove" />}>
        <StyledSpan>
          <StyledIconButton
            className="removeRecentIcon"
            onClick={removeRecentFile}
            disabled={isProcessing}
          >
            <img
              style={{
                width: "13px"
              }}
              data-component="removeThumbnailIcon"
              src={closeSVG}
              alt="removeRecentFile"
            />
          </StyledIconButton>
        </StyledSpan>
      </Tooltip>
      <FileLink
        fileLink={fileLink}
        calculatedCallback={fileLinkWidthCalculated}
        dataComponent="recentFileLink"
        name={name}
      />
      <FileInfo
        storageName={storageName}
        date={date}
        dataComponent="recentFileDetails"
        name={name}
      />
    </StyledDivContainer>
  );
}
