import { ListItem, ListItemText } from "@mui/material";
import React, { useMemo } from "react";
import { FormattedMessage } from "react-intl";
import { Link } from "react-router";

type PropType = {
  isMobile: boolean;
  item: {
    link: string;
    messageId: string;
    dataComponent: string;
  };
};

export default function SidebarItem({ isMobile, item }: PropType) {
  const { link, messageId, dataComponent } = item;
  const { pathname } = location;
  const pathMap = useMemo<Record<string, string>>(
    () => ({
      "/profile": "/profile/account",
      "/profile/": "/profile/account"
    }),
    []
  );

  const isActive = useMemo(
    () => (pathMap[pathname] ?? pathname) === link,
    [pathname, pathMap]
  );

  return (
    <Link to={link} key={link} data-component={dataComponent}>
      <ListItem
        sx={
          isActive
            ? {
                backgroundColor: theme =>
                  theme.palette.drawer.item.background.active
              }
            : {}
        }
      >
        <ListItemText
          primaryTypographyProps={{
            sx: {
              fontSize: theme => theme.spacing(1.5, "!important"),
              color: theme => theme.palette.drawer.item.textColor,
              opacity: isMobile ? 0 : 1,
              pl: 3.5
            }
          }}
        >
          <FormattedMessage id={messageId} />
        </ListItemText>
      </ListItem>
    </Link>
  );
}
