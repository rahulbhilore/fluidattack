import React from "react";
import { FormattedMessage } from "react-intl";
import { styled } from "@material-ui/core";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import MainFunctions from "../../../libraries/MainFunctions";

const StyledButton = styled(KudoButton)(() => ({
  marginLeft: "auto"
}));

const ARES_DOWNLOAD_LINK =
  "https://www.graebert.com/cad-software/ares-commander";

const TOUCH_PAGE_LINK = "https://www.graebert.com/cad-software/ares-touch";

export default function openInCommander({ info }) {
  const mobile = MainFunctions.isMobileDevice();
  const { needSave } = info;

  return (
    <>
      <DialogBody>
        {needSave ? (
          <FormattedMessage
            id={
              mobile
                ? "tryingToLaunchTouchWithSave"
                : "tryingToLaunchAresWithSave"
            }
          />
        ) : (
          <FormattedMessage
            id={mobile ? "tryingToLaunchTouch" : "tryingToLaunchAres"}
          />
        )}{" "}
        {mobile ? (
          <a
            href={TOUCH_PAGE_LINK}
            target="_blank"
            rel="noreferrer"
            aria-label="Download ARES Touch"
          >
            <FormattedMessage id="downloadTouch" />
          </a>
        ) : (
          <a
            href={ARES_DOWNLOAD_LINK}
            target="_blank"
            rel="noreferrer"
            aria-label="Download ARES Commander"
          >
            <FormattedMessage id="downloadAres" />
          </a>
        )}
      </DialogBody>
      <DialogFooter showCancel={false}>
        <StyledButton isDisabled={false} onClick={ModalActions.hide}>
          <FormattedMessage id="ok" />
        </StyledButton>
      </DialogFooter>
    </>
  );
}
