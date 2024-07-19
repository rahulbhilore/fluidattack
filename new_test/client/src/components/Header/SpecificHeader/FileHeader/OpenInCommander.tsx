import React from "react";
import { FormattedMessage } from "react-intl";
import { Tooltip, Box, Button } from "@mui/material";
import MainFunctions, {
  APPLE_DEVICE
} from "../../../../libraries/MainFunctions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import ModalActions from "../../../../actions/ModalActions";
import FilesListStore from "../../../../stores/FilesListStore";
import FilesListActions from "../../../../actions/FilesListActions";
import modalStore from "../../../../stores/ModalStore";
import * as ModalConstants from "../../../../constants/ModalConstants";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";
import IconPNG from "../../../../assets/images/icon.png";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";

type Props = {
  id: string;
  folderId: string;
  name: string;
  isForMobileHeader?: boolean;
  onMenuItemClick?: () => void;
};

const OpenInCommander: React.FC<Props> = ({
  id,
  folderId,
  name,
  isForMobileHeader = false,
  onMenuItemClick
}) => {
  const mobile = MainFunctions.isMobileDevice();
  const token: string = MainFunctions.QueryString("token") as string;

  // isFreeAccount will check license only for "prod" instances, so better to use it here for consistency
  const isFreeAccount = UserInfoStore.isFreeAccount();
  const isInIframe = MainFunctions.isInIframe();
  const hasCommanderLicense = UserInfoStore.getUserInfo("hasCommanderLicense");
  const hasTouchLicense = UserInfoStore.getUserInfo("hasTouchLicense");
  const hasAppropriateLicense =
    (mobile && hasTouchLicense) || (!mobile && hasCommanderLicense);
  const isDisabled: boolean =
    (mobile && !!token) ||
    (!token && (isFreeAccount || isInIframe || !hasAppropriateLicense));

  let disableMessage: React.ReactNode = "";
  if (isDisabled) {
    if (isFreeAccount || !hasAppropriateLicense) {
      disableMessage = <FormattedMessage id="noUserLicense" />;
    } else {
      disableMessage = <FormattedMessage id="notAvailableInsideIframe" />;
    }
  }

  const openCommander = async () => {
    // if it's opened for edit - invoke save and await for save!
    ModalActions.openInCommander(FilesListStore.getCurrentFile().isModified);
    FilesListActions.saveFileInXenon()
      .then(() => {
        FilesListActions.openFileInCommander(id, folderId, {
          name,
          versionId: MainFunctions.QueryString("versionId") as string,
          token
        })
          .then(() => {
            // make sure we only close this dialog
            if (
              modalStore.getCurrentDialogType() ===
              ModalConstants.OPEN_IN_COMMANDER
            ) {
              ModalActions.hide();
            }
          })
          .catch(error => {
            if (error?.message === APPLE_DEVICE) {
              setTimeout(() => {
                if (
                  modalStore.getCurrentDialogType() ===
                  ModalConstants.OPEN_IN_COMMANDER
                ) {
                  ModalActions.hide();
                }
              }, 5000);
            }
          });
      })
      .catch(() => {
        SnackbarUtils.alertError({ id: "saveFileError" });
      })
      .finally(() => {
        if (isForMobileHeader && onMenuItemClick) {
          onMenuItemClick();
        }
        if (
          modalStore.getCurrentDialogType() === ModalConstants.OPEN_IN_COMMANDER
        ) {
          ModalActions.hide();
        }
      });
  };

  if (isForMobileHeader && !isDisabled)
    return (
      <DrawingMenuItem
        onClick={openCommander}
        icon={IconPNG}
        caption={
          mobile ? (
            <FormattedMessage id="openInTouch" />
          ) : (
            <FormattedMessage id="openInCommander" />
          )
        }
        dataComponent="open_in_commander_menu"
      />
    );

  return (
    <Tooltip title={disableMessage}>
      <Box data-component="open_in_commander" display="inline">
        <Button
          onClick={openCommander}
          disabled={isDisabled}
          sx={{
            color: theme => theme.palette.LIGHT,
            "&.Mui-disabled": {
              color: "rgba(255, 255, 255, 0.3)"
            },
            borderRadius: 0,
            height: "36px",
            padding: "0 10px",
            textTransform: "initial",
            fontSize: theme => theme.typography.pxToRem(12)
          }}
        >
          <img
            style={{ width: "18px", marginRight: "5px" }}
            src={IconPNG}
            alt="icon"
          />
          {mobile ? (
            <FormattedMessage id="openInTouch" />
          ) : (
            <FormattedMessage id="openInCommander" />
          )}
        </Button>
      </Box>
    </Tooltip>
  );
};

export default OpenInCommander;
