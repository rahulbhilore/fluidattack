import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { ReactSVG } from "react-svg";
import trashSVG from "../../assets/images/trash.svg";
import homeSVG from "../../assets/images/home.svg";

const useStyles = makeStyles(theme => ({
  root: {
    width: 20,
    height: 20,
    "&:hover svg,&:focus svg,&:active svg": {
      fill: theme.palette.OBI
    }
  }
}));

export default function HomeIcon({ isTrash }) {
  const classes = useStyles();
  return (
    <ReactSVG src={isTrash ? trashSVG : homeSVG} className={classes.root} />
  );
}

HomeIcon.propTypes = {
  isTrash: PropTypes.bool
};

HomeIcon.defaultProps = {
  isTrash: false
};
