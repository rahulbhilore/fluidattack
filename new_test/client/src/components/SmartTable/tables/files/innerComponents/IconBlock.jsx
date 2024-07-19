import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { makeStyles } from "@material-ui/core/styles";
import Box from "@material-ui/core/Box";
import MainFunctions from "../../../../../libraries/MainFunctions";
import iconsDictionary from "../../../../../constants/appConstants/ObjectIcons";
import userInfoStore from "../../../../../stores/UserInfoStore";
import Thumbnail from "../../../../Thumbnail";

const useStyles = makeStyles(() => ({
  root: {
    height: "100%",
    width: "68px",
    display: "flex",
    position: "absolute",
    justifyContent: "center",
    alignItems: "center",
    "& img": {
      maxWidth: "100%",
      maxHeight: "100%"
    }
  },
  renameMode: {
    marginTop: "-22px"
  },
  genericIcon: {
    height: "32px",
    width: "36px"
  },
  xref: {
    height: "36px",
    width: "36px",
    "& img": {
      maxWidth: "80%",
      maxHeight: "80%",
      margin: 0
    }
  }
}));

function getExtension(type, name) {
  return type === "folder"
    ? "folder"
    : MainFunctions.getExtensionFromName(name);
}

function IconBlock({
  name,
  type,
  isShortcut,
  icon,
  shared,
  mimeType,
  thumbnail,
  _id,
  isScrolling,
  renameMode,
  thumbnailStatus
}) {
  const classes = useStyles();
  const [renderIcon, setIcon] = useState(null);

  const getImageComponent = src => (
    <img src={src} alt={name} className={classes.genericIcon} />
  );

  const getRenderIcon = () => {
    const ext = getExtension(type, name);
    // shortcut
    if (isShortcut) {
      setIcon(
        getImageComponent(
          type.endsWith("folder")
            ? iconsDictionary.folderShortcutSVG
            : iconsDictionary.fileShortcutSVG
        )
      );
    }
    // folder
    else if (type === "folder") {
      let svgLink = iconsDictionary.folderSVG;
      if (icon) {
        svgLink = icon;
      } else if (shared) {
        svgLink = iconsDictionary.folderSharedSVG;
      }
      setIcon(getImageComponent(svgLink));
    }
    // file that can be opened in xenon
    else if (userInfoStore.findApp(ext, mimeType) === "xenon" && thumbnail) {
      setIcon(
        <Thumbnail
          thumbnailStatus={thumbnailStatus}
          src={thumbnail}
          fileId={_id}
        />
      );
    }
    // something else
    else {
      const svgType = userInfoStore.getIconClassName(ext, type, name, mimeType);
      // just in case, but we should
      // always be able to get from the dictionary.
      let svgLink = `images/icons/${svgType}.svg`;
      if (
        Object.prototype.hasOwnProperty.call(iconsDictionary, `${svgType}SVG`)
      ) {
        svgLink = iconsDictionary[`${svgType}SVG`];
      }
      setIcon(getImageComponent(svgLink));
    }
  };
  useEffect(() => {
    if (isScrolling && renderIcon === null) {
      if (type === "folder") {
        setIcon(getImageComponent(iconsDictionary.folderSVG));
      } else {
        setIcon(getImageComponent(iconsDictionary.fileSVG));
      }
    }
  }, [isScrolling]);
  useEffect(getRenderIcon, [
    name,
    type,
    icon,
    shared,
    mimeType,
    thumbnail,
    _id
  ]);
  return (
    <Box
      className={clsx(
        classes.root,
        classes[type],
        renameMode ? classes.renameMode : null
      )}
    >
      {renderIcon}
    </Box>
  );
}

IconBlock.propTypes = {
  name: PropTypes.string.isRequired,
  icon: PropTypes.string,
  shared: PropTypes.bool,
  mimeType: PropTypes.string,
  thumbnail: PropTypes.string,
  _id: PropTypes.string.isRequired,
  type: PropTypes.string,
  isShortcut: PropTypes.bool,
  isScrolling: PropTypes.bool,
  renameMode: PropTypes.bool,
  thumbnailStatus: PropTypes.string
};

IconBlock.defaultProps = {
  type: "file",
  isShortcut: false,
  icon: null,
  shared: false,
  mimeType: null,
  thumbnail: null,
  isScrolling: false,
  renameMode: false,
  thumbnailStatus: ""
};
export default React.memo(IconBlock);
