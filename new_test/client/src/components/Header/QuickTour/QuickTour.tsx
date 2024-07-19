import { Button, Grid, Modal, styled } from "@mui/material";
import React, { useEffect, useRef, useState } from "react";
import crossSVG from "../../../assets/images/CloseLight.svg";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore, { UPDATE } from "../../../stores/ApplicationStore";
import Tracker from "../../../utils/Tracker";

const initialVideoHeight = 720;
const initialVideoWidth = 1080;

const minWidth = 200;
const minHeight = 200;

type PropType = {
  isLoggedIn: boolean;
  isOpen: boolean;
  onClose: () => void;
};

export default function QuickTour({
  isOpen = false,
  isLoggedIn = false,
  onClose = () => null
}: PropType) {
  const [videoHeight, setVideoHeight] = useState(initialVideoHeight);
  const [videoWidth, setVideoWidth] = useState(initialVideoWidth);
  const [top, setTop] = useState<number>();
  const [left, setLeft] = useState<number>();
  const [language, setLanguage] = useState(
    ApplicationStore.getApplicationSetting("language")
  );
  const isOpenRef = useRef(isOpen);

  const calculateSize = (isInitialSetup: boolean) => {
    const minPadding = 20;
    let newVideoWidth = initialVideoWidth;
    let newVideoHeight = initialVideoHeight;
    const videoAspectRatio = newVideoWidth / newVideoHeight;
    const windowHeight = document.body.offsetHeight;
    const windowWidth = document.body.offsetWidth;
    const maxVideoHeight = Math.min(
      windowHeight - minPadding * 2,
      initialVideoHeight
    );
    const maxVideoWidth = Math.min(
      windowWidth - minPadding * 2,
      initialVideoWidth
    );
    // fit video size between min and max values
    if (newVideoWidth > maxVideoWidth) {
      newVideoWidth = maxVideoWidth;
      newVideoHeight = Math.floor(newVideoWidth / videoAspectRatio);
    }
    if (newVideoHeight > maxVideoHeight) {
      newVideoHeight = maxVideoHeight;
      newVideoWidth = Math.floor(newVideoHeight * videoAspectRatio);
    }
    if (newVideoWidth < minWidth) {
      newVideoWidth = minWidth;
      newVideoHeight = Math.floor(newVideoWidth / videoAspectRatio);
    }
    if (newVideoHeight < minHeight) {
      newVideoHeight = minHeight;
      newVideoWidth = Math.floor(newVideoHeight * videoAspectRatio);
    }
    const topOffset = (windowHeight - newVideoHeight) / 2;
    const leftOffset = (windowWidth - newVideoWidth) / 2;
    if (
      isInitialSetup ||
      videoHeight !== newVideoHeight ||
      videoWidth !== newVideoWidth ||
      top !== topOffset ||
      left !== leftOffset
    ) {
      setTop(topOffset);
      setLeft(leftOffset);
      setVideoHeight(newVideoHeight);
      setVideoWidth(newVideoWidth);
    }
  };

  const onAppUpdate = () => {
    if (language !== ApplicationStore.getApplicationSetting("language")) {
      setLanguage(ApplicationStore.getApplicationSetting("language"));
    }
  };

  const onResize = () => {
    calculateSize(false);
  };

  const getVideoPerLanguage = () => {
    const token = MainFunctions.QueryString("token") || "";
    const isTokenAccess = (token?.length as number) > 0;
    if (isTokenAccess && !isLoggedIn) {
      switch (language) {
        case "de":
          return "https://www.youtube.com/embed/IL4HGrt7GFE";
        case "ja":
          return "https://www.youtube.com/embed/-SPWQvAofh0";
        case "ko":
          return "https://www.youtube.com/embed/GlYjl_Zrd4w";
        case "pt":
          return "https://www.youtube.com/embed/0ZMuc-prkFI";
        case "pl":
          return "https://www.youtube.com/embed/Av5F9UsmkPA";
        default:
          return "https://www.youtube.com/embed/qsdtf6moFh0";
      }
    } else {
      switch (language) {
        case "de":
          return "https://www.youtube.com/embed/5Zq7rzGpArE";
        case "ja":
          return "https://www.youtube.com/embed/lnevjQGboIQ";
        case "ko":
          return "https://www.youtube.com/embed/JZcjLJJTE9k";
        case "pt":
          return "https://www.youtube.com/embed/J9ZKWK4VfFM";
        case "pl":
          return "https://www.youtube.com/embed/gOWKCyt7flg";
        default:
          return "https://www.youtube.com/embed/Jbh1KXWBKlU";
      }
    }
  };

  const buildModalBody = () => {
    const BodyContainer = styled(Grid)(() => ({
      top: top || 0,
      left: left || 0,
      position: "absolute",
      display: "block",
      width: `${videoWidth}px`
    }));
    const CrossContainer = styled(Grid)(() => ({
      float: "right"
    }));
    const StyledCross = styled("img")(() => ({
      width: "20px"
    }));
    const Iframe = styled("iframe")(() => ({
      width: `${videoWidth}px`,
      height: `${videoHeight}px`,
      border: 0
    }));
    const StyledButton = styled(Button)(() => ({
      padding: 0,
      minWidth: 0,
      borderRadius: "50px"
    }));
    return (
      <BodyContainer container direction="column">
        <CrossContainer item>
          <StyledButton onClick={onClose}>
            <StyledCross src={crossSVG} alt="cross" />
          </StyledButton>
        </CrossContainer>
        <Iframe
          src={getVideoPerLanguage()}
          title="ARES Kudo Quick Tour"
          allow="autoplay; encrypted-media"
          allowFullScreen
        />
      </BodyContainer>
    );
  };

  useEffect(() => {
    const isOpenPrev = isOpenRef.current;
    if (isOpen && !isOpenPrev) {
      Tracker.sendGAEvent("Video", "ARES Kudo");
    }
    isOpenRef.current = isOpen;
  }, [isOpen]);

  useEffect(() => {
    calculateSize(true);
    const observer = new ResizeObserver(() => {
      onResize();
    });
    observer.observe(document.body);
    ApplicationStore.addListener(UPDATE, onAppUpdate);

    return () => {
      observer.unobserve(document.body);
      ApplicationStore.removeListener(UPDATE, onAppUpdate);
    };
  }, []);

  return (
    <Modal open={isOpen} onClose={onClose}>
      {buildModalBody()}
    </Modal>
  );
}
