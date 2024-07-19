import React from "react";
import propTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import SVG from "react-inlinesvg";
import clsx from "clsx";
import MainFunctions from "../../../libraries/MainFunctions";
import iconsDictionary from "../../../constants/appConstants/ObjectIcons";
import UserInfoStore from "../../../stores/UserInfoStore";
import Thumbnail from "../../Thumbnail";

const useStyles = makeStyles(theme => ({
  icon: {
    width: "20px",
    height: "20px",
    "&.unavailable > path": {
      fill: theme.palette.REY
    },
    "&.img": {
      marginTop: 0
    }
  }
}));

export default function Icon({
  id,
  name,
  type,
  url,
  mimeType,
  isAbleToMove,
  isSelected
}) {
  const classes = useStyles();
  const ext = MainFunctions.getExtensionFromName(name);
  const isThumbnail =
    url &&
    type !== "folder" &&
    UserInfoStore.findApp(ext, mimeType) === "xenon";
  const classNames = clsx(classes.icon, !isAbleToMove ? "unavailable" : "");
  if (isThumbnail) {
    return <Thumbnail className={classNames} src={url} name={name} />;
  }
  if (url && type === "folder") {
    return <img src={url} alt={name} className={clsx(classNames, "img")} />;
  }

  const iconSrc = (() => {
    if (id === "-1") return iconsDictionary.homeSVG;
    if (isSelected) return iconsDictionary.whiteFolderSVG;
    const svgType = UserInfoStore.getIconClassName(ext, type, name, mimeType);
    // just in case, but we should
    // always be able to get from the dictionary.
    let svgLink = `images/icons/${svgType}.svg`;
    if (
      Object.prototype.hasOwnProperty.call(iconsDictionary, `${svgType}SVG`)
    ) {
      svgLink = iconsDictionary[`${svgType}SVG`];
    }
    return svgLink;
  })();

  return <SVG src={iconSrc} alt={name} className={classNames} />;
}

Icon.propTypes = {
  id: propTypes.string.isRequired,
  name: propTypes.string.isRequired,
  type: propTypes.string.isRequired,
  url: propTypes.string,
  mimeType: propTypes.string,
  isAbleToMove: propTypes.bool,
  isSelected: propTypes.bool
};

Icon.defaultProps = {
  url: "",
  mimeType: "",
  isAbleToMove: true,
  isSelected: false
};
