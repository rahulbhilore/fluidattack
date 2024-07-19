import { Box, IconButton, Portal } from "@mui/material";
import React, { useCallback, useEffect, useState } from "react";
import { useSpring, animated } from "@react-spring/web";
import Storage from "../utils/Storage";
import ApplicationStore, {
  CONFIG_LOADED,
  UPDATE
} from "../stores/ApplicationStore";
import FilesListStore, {
  CURRENT_FILE_INFO_UPDATED
} from "../stores/FilesListStore";
import MainFunctions from "../libraries/MainFunctions";
import userInfoStore, { STORAGES_UPDATE } from "../stores/UserInfoStore";
import { StorageType, UserStoragesInfo } from "../types/StorageTypes";

export const A3_IFRAME_ID = "a3_assistant_frame";

type StorageConfig = {
  serviceName: string;
  isConnectable: boolean;
  displayName: string;
};

function A3() {
  const [open, setOpen] = useState(false);
  const [props, api] = useSpring(() => ({
    from: { y: window.innerHeight }
  }));

  const toggleOpened = useCallback(() => {
    setOpen(!open);
    api.start({
      from: { y: open ? 0 : window.innerHeight },
      to: { y: open ? window.innerHeight : 0 }
    });
  }, [open]);

  const [additionalInstruction, setAdditionalInstruction] = useState("");

  const handleLocationChange = useCallback(() => {
    // remove unnecessary listeners
    userInfoStore.removeChangeListener(STORAGES_UPDATE, handleLocationChange);
    FilesListStore.removeEventListener(
      CURRENT_FILE_INFO_UPDATED,
      handleLocationChange
    );

    const { pathname } = window.location;
    let locationInstruction = `User is viewing page: ${pathname}`;
    if (pathname.includes("/files/")) {
      // files page
      locationInstruction = "User is viewing files list.";
      const currentFolder = FilesListStore.getCurrentFolder();
      if ((currentFolder?.storage || "").length > 0) {
        const userStorageName = MainFunctions.serviceNameToUserName(
          MainFunctions.storageCodeToServiceName(currentFolder?.storage || "")
        );
        locationInstruction += ` User is in ${userStorageName} storage. The folder name is ${currentFolder?.name}.`;
      }
    } else if (pathname.includes("/file/")) {
      // files page
      locationInstruction = "User has the drawing file opened.";
      const currentFile = FilesListStore.getCurrentFile();
      if ((currentFile?._id || "").length > 0) {
        const userStorageName = MainFunctions.serviceNameToUserName(
          MainFunctions.storageCodeToServiceName(
            FilesListStore.findCurrentStorage(currentFile._id).storageType
          )
        );
        locationInstruction += ` User is in ${userStorageName} storage. The file name is ${currentFile?.name}. The file is ${
          currentFile.viewOnly ? "view only" : "editable"
        }.`;
      }
      FilesListStore.addEventListener(
        CURRENT_FILE_INFO_UPDATED,
        handleLocationChange
      );
    } else if (pathname.includes("/storages")) {
      locationInstruction = "User is viewing storages list.";
      const storagesConnected =
        userInfoStore.getStoragesInfo() as UserStoragesInfo;
      if (Object.keys(storagesConnected).length > 0) {
        const userOptions = userInfoStore.getUserInfo("options");
        const storagesConfig = userInfoStore.getStoragesConfig();
        const availableStorages = (
          Object.values(storagesConfig) as Array<StorageConfig>
        )
          .filter(storageSettings => {
            if (storageSettings.serviceName === "internal") return false;
            if (!storageSettings.isConnectable) return false;
            if (userOptions.storages[storageSettings.serviceName] === false) {
              return false;
            }
            if (
              userOptions.storages[
                storageSettings.serviceName.toLowerCase()
              ] === false
            ) {
              return false;
            }
            return true;
          })
          .map(storageConfig => ({
            name: storageConfig.serviceName.toLowerCase(),
            displayName: storageConfig.displayName
          }));
        const availableConnectedStorages = Object.keys(
          storagesConnected
        ).filter(
          (storageKey: StorageType) =>
            availableStorages.find(
              st => st.name === storageKey.toLowerCase()
            ) !== undefined
        );
        if (availableConnectedStorages.length > 0) {
          locationInstruction += `User has the following storages: ${availableConnectedStorages
            .map(
              (storageKey: StorageType) =>
                `${MainFunctions.serviceNameToUserName(storageKey)} - ${
                  storagesConnected[storageKey].length
                } accounts connected`
            )
            .join(", ")}`;
        }
      }
      // DK: I'm not sure if we need to always listen to it or only in some cases
      userInfoStore.addChangeListener(STORAGES_UPDATE, handleLocationChange);
    } else if (pathname.includes("/resources")) {
      let resourceType = "owned templates";
      if (pathname.includes("/templates/public")) {
        resourceType = "public templates";
      } else if (pathname.includes("/fonts/my")) {
        resourceType = "owned fonts";
      } else if (pathname.includes("/fonts/public")) {
        resourceType = "public fonts";
      } else if (pathname.includes("/blocks")) {
        resourceType = "blocks";
      }
      locationInstruction = `User is viewing ${resourceType} list.`;
    } else if (pathname.includes("/profile")) {
      locationInstruction = "User is viewing his profile page.";
      if (pathname.includes("/profile/preferences")) {
        locationInstruction = "User is viewing his preferences page.";
      }
    } else if (pathname.includes("/company")) {
      locationInstruction = "User is viewing his company options page.";
    } else if (pathname.includes("/users")) {
      locationInstruction = "User is viewing ARES Kudo users list.";
    } else if (pathname.includes("/check")) {
      locationInstruction = "User is viewing WebGL check page.";
    }
    setAdditionalInstruction(locationInstruction);
  }, []);

  useEffect(() => {
    (
      document.getElementById(A3_IFRAME_ID) as HTMLIFrameElement
    )?.contentWindow?.postMessage(
      { type: "A3_CONTEXT", context: additionalInstruction },
      "*"
    );
  }, [additionalInstruction]);

  useEffect(() => {
    handleLocationChange();
    ApplicationStore.addChangeListener(UPDATE, handleLocationChange);
    ApplicationStore.addChangeListener(CONFIG_LOADED, handleLocationChange);
    return () => {
      ApplicationStore.removeChangeListener(UPDATE, handleLocationChange);
      ApplicationStore.removeChangeListener(
        CONFIG_LOADED,
        handleLocationChange
      );
    };
  }, []);

  const AnimatedBox = animated(Box);

  const A3URL = ApplicationStore.getApplicationSetting("a3url");

  const revision = ApplicationStore.getApplicationSetting("revision");
  const release = revision.split(".", 2).join(".");

  const sid = Storage.getItem("sessionId") || "";

  if (!A3URL.length || !sid.length) {
    return null;
  }

  return (
    <Portal>
      <Box
        sx={{
          position: "absolute",
          bottom: 8,
          right: 8,
          display: "flex",
          flexDirection: "column",
          gap: 1,
          pointerEvents: "none"
        }}
        data-component="a3-wrapper"
      >
        <AnimatedBox
          style={{ ...props }}
          sx={{ pointerEvents: "auto" }}
          data-component="a3-iframe-wrapper"
        >
          <iframe
            data-component="a3-iframe"
            id={A3_IFRAME_ID}
            title="A3 - AI Assistant"
            src={`${A3URL}/embed/?sid=${sid}&ku=${ApplicationStore.getApplicationSetting(
              "apiURL"
            )}&application=kudo&licenseVer=${release}&sysServiceVer=${revision}&language=${Storage.getItem(
              "lang"
            )}&theme=dark&cmode=kudo`}
            style={{
              border: "none",
              boxShadow: "0 0 2px 0 rgba(0, 0, 0, 0.2)",
              width: "400px",
              height: "50svh"
            }}
          />
        </AnimatedBox>
        <IconButton
          sx={{
            borderRadius: "50%",
            alignSelf: "end",
            bgcolor: "#333538",
            width: "44px",
            height: "44px",
            p: 1,
            "&:hover,&:focus,&:active": {
              bgcolor: "#E9601C"
            },
            pointerEvents: "auto"
          }}
          onClick={toggleOpened}
          data-component="a3-trigger-button"
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="26"
            height="23"
            viewBox="0 0 26 23"
            fill="none"
          >
            <path
              d="M16.5455 4.72729C17.1724 4.72729 17.7736 4.97632 18.2168 5.41959C18.6601 5.86286 18.9091 6.46406 18.9091 7.09093C18.9091 7.71781 18.6601 8.31901 18.2168 8.76228C17.7736 9.20554 17.1724 9.45457 16.5455 9.45457C15.9186 9.45457 15.3174 9.20554 14.8741 8.76228C14.4309 8.31901 14.1818 7.71781 14.1818 7.09093C14.1818 6.46406 14.4309 5.86286 14.8741 5.41959C15.3174 4.97632 15.9186 4.72729 16.5455 4.72729Z"
              fill="#CFCFCF"
            />
            <path
              d="M8.27272 4.72729C8.89959 4.72729 9.50079 4.97632 9.94406 5.41959C10.3873 5.86286 10.6364 6.46406 10.6364 7.09093C10.6364 7.71781 10.3873 8.31901 9.94406 8.76228C9.50079 9.20554 8.89959 9.45457 8.27272 9.45457C7.64584 9.45457 7.04464 9.20554 6.60137 8.76228C6.1581 8.31901 5.90908 7.71781 5.90908 7.09093C5.90908 6.46406 6.1581 5.86286 6.60137 5.41959C7.04464 4.97632 7.64584 4.72729 8.27272 4.72729Z"
              fill="#CFCFCF"
            />
            <path
              d="M24.8182 5.90906H26V11.8182H24.8182V5.90906Z"
              fill="#EF9327"
            />
            <path
              d="M5.00679e-06 5.90906H1.18182V11.8182H5.00679e-06V5.90906Z"
              fill="#EF9327"
            />
            <path
              d="M22.4546 11.8181H3.54549V13.3545C3.54549 14.4181 4.37276 15.3636 5.55458 15.3636H19.7955C21.2728 15.3636 22.4546 14.1818 22.4546 12.7636V11.8181Z"
              fill="#CFCFCF"
            />
            <path
              d="M10.6364 22.4546L17.7273 16.5455V15.3636H10.6364V16.5455V22.4546Z"
              fill="#CFCFCF"
            />
            <path
              d="M7.32726 1.18182C5.25907 1.18182 3.66362 2.65909 3.54544 4.66818V11.8773C3.54544 13.7682 5.19998 15.3636 7.20907 15.3636H18.7909C20.8 15.3636 22.4545 13.8273 22.4545 11.8773V4.66818C22.4545 2.71818 20.9182 1.18182 18.9091 1.18182H7.32726ZM7.32726 0H18.9091C21.5091 0 23.6364 2.00909 23.6364 4.66818V11.9364C23.6364 14.4182 21.5091 16.6046 18.7909 16.6046H7.14998C4.54998 16.6046 2.30453 14.5955 2.30453 11.9364V4.66818C2.4818 2.00909 4.60907 0 7.32726 0Z"
              fill="#CFCFCF"
            />
          </svg>
        </IconButton>
      </Box>
    </Portal>
  );
}
export default React.memo(A3);
