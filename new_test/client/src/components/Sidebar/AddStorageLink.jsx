import React from "react";
import { Link } from "react-router";
import { FormattedMessage } from "react-intl";
import { makeStyles } from "@material-ui/core/styles";
import { IconButton } from "@material-ui/core";
import AddIcon from "@material-ui/icons/Add";
import clsx from "clsx";
import storageIconSVG from "../../assets/images/userMenu/storage.svg";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.VADER,
    display: "block",
    width: "100%",
    padding: 10,
    fontSize: 12,
    color: theme.palette.LIGHT,
    textDecoration: "none",
    "&:hover, &:focus, &:active, &:visited": {
      color: theme.palette.LIGHT,
      textDecoration: "none"
    },
    [theme.breakpoints.down("xs")]: {
      padding: "8px 10px"
    }
  },
  icon: {
    width: 20,
    height: 20,
    marginRight: theme.spacing(1),
    verticalAlign: "middle"
  },
  addSign: {
    float: "right",
    margin: "0 10px",
    verticalAlign: "middle",
    padding: 0,
    "&:hover,&:focus,&:active": {
      backgroundColor: "transparent"
    }
  },
  addIcon: {
    fontSize: theme.typography.pxToRem(20)
  }
}));

export default function AddStorageLink() {
  const classes = useStyles();
  return (
    <Link to="/storages" className={clsx("addStorageLink", classes.root)}>
      <img src={storageIconSVG} alt="Storage" className={classes.icon} />
      <FormattedMessage id="Storage" />
      <IconButton
        className={classes.addSign}
        data-component="add-storage-button"
      >
        <AddIcon className={classes.addIcon} />
      </IconButton>
    </Link>
  );
}
