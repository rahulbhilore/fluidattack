import React from "react";
import clsx from "clsx";
import { useIntl } from "react-intl";
import { Link } from "react-router";
import { makeStyles } from "@mui/styles";
import { Theme } from "@mui/material/styles";
import ListItem from "@mui/material/ListItem";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";

const useStyles = makeStyles((theme: Theme) => ({
  listImage: {
    width: "23px",
    minWidth: "23px!important",
    maxHeight: 23,
    marginRight: 10,
    "& img": {
      width: 20,
      maxHeight: 20
    }
  },
  listText: {
    fontSize: "12px!important",
    color: theme.palette.REY
  },
  active: {
    color: theme.palette.LIGHT
  }
}));

type Props = {
  item: {
    link: string;
    messageId: string;
    icon: string;
    dataComponent: string;
  };
};

export default function SideBarItem({ item }: Props) {
  const { formatMessage } = useIntl();
  const classes = useStyles();

  const { link, messageId, icon, dataComponent } = item;

  const isActive = () => {
    const { pathname } = location;
    return pathname === link;
  };

  return (
    <Link to={link} key={link} data-component={dataComponent}>
      <ListItem>
        <ListItemIcon className={classes.listImage}>
          <img src={icon} alt={messageId} />
        </ListItemIcon>
        <ListItemText
          primaryTypographyProps={{
            className: clsx(
              classes.listText,
              isActive() ? classes.active : null
            )
          }}
        >
          {formatMessage({ id: messageId })}
        </ListItemText>
      </ListItem>
    </Link>
  );
}
