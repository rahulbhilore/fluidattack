/**
 * Created by khizh on 8/25/2016.
 */
import React from "react";
import { FormattedMessage } from "react-intl";
import UserInfoActions from "../../../actions/UserInfoActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

export default function connectStorage({ info }) {
  let storage = info.storageType;
  if (storage === "gdrive") {
    storage = "Google Drive";
  }
  return (
    <>
      <DialogBody>
        <FormattedMessage id="connectStorageQuestion" values={{ storage }} />
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isDisabled={false}
          onClick={() => {
            const currentPageUrl =
              (location.pathname.substr(
                location.pathname.indexOf(
                  window.ARESKudoConfigObject.UIPrefix
                ) + 1
              ) || "files") + location.search;
            UserInfoActions.connectStorage(
              this.props.info.storageType,
              encodeURIComponent(currentPageUrl)
            );
          }}
        >
          <FormattedMessage id="connect" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
