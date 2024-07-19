import { Menu, Typography, menuClasses } from "@mui/material";
import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import FilesListStore from "../../../../stores/FilesListStore.js";
import OpenInCommander from "../FileHeader/OpenInCommander";
import LoginMenuItem from "./LoginMenuItem";

type PropType = {
  isVisible: boolean;
  closeMenu: () => void;
  login: () => void;
  signUpRedirect: () => void;
  showOpenInAppButton: boolean;
};

export default function LoginMenu({
  isVisible,
  closeMenu,
  login,
  signUpRedirect,
  showOpenInAppButton
}: PropType) {
  const [anchorEl, setAnchorEl] = useState<Element | null>(null);

  const currentFile = FilesListStore.getCurrentFile();

  useEffect(() => {
    if (!isVisible) {
      setAnchorEl(null);
    } else {
      const el = document.getElementById("logo-menu-button-id");
      setAnchorEl(el);
    }
  }, [isVisible]);

  return (
    <Menu
      id="login-menu-header"
      anchorEl={anchorEl}
      elevation={0}
      open={Boolean(anchorEl)}
      anchorOrigin={{
        vertical: "bottom",
        horizontal: "left"
      }}
      transformOrigin={{ vertical: "top", horizontal: "right" }}
      onClose={closeMenu}
      sx={{
        [`& .${menuClasses.paper}`]: {
          padding: 0,
          backgroundColor: theme => theme.palette.DARK,
          border: theme => `solid 1px ${theme.palette.VADER}`,
          borderRadius: 0,
          width: "200px"
        },
        [`& .${menuClasses.list}`]: {
          padding: 0
        }
      }}
    >
      {showOpenInAppButton && (
        <OpenInCommander
          id={currentFile._id}
          folderId={currentFile.folderId}
          name={currentFile.name}
          isForMobileHeader
        />
      )}
      <Typography
        sx={{
          color: theme => theme.palette.LIGHT,
          padding: "10px 10px 2px 16px;",
          fontSize: 12
        }}
      >
        <FormattedMessage id="wantToCommentDrawing" />
      </Typography>
      <LoginMenuItem onClick={login}>
        <FormattedMessage id="login" />
      </LoginMenuItem>
      <LoginMenuItem onClick={signUpRedirect}>
        <FormattedMessage id="signUp" />
      </LoginMenuItem>
    </Menu>
  );
}
