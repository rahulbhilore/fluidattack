import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Link } from "react-router";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import { Box, Tooltip } from "@material-ui/core";
import clsx from "clsx";
import FileSVG from "../../../../assets/images/icons/file.svg";
import FolderSVG from "../../../../assets/images/icons/folder.svg";
import FolderSharedSVG from "../../../../assets/images/icons/folderShared.svg";
import Thumbnail from "../../../Thumbnail";
import userInfoStore from "../../../../stores/UserInfoStore";
import { BLOCK, LIBRARY } from "../../../../stores/BlocksStore";

const useStyles = makeStyles(theme => ({
  root: {
    display: "flex",
    alignItems: "center",
    paddingLeft: theme.spacing(2)
  },
  genericIcon: {
    height: "32px",
    width: "36px"
  },
  img: {
    marginLeft: "26px",
    width: "40px"
  },
  nameBlock: {
    padding: "0 10px 0 88px",
    width: "100%",
    display: "inline-block"
  },
  name: {
    marginLeft: theme.spacing(2),
    color: theme.palette.VADER,
    cursor: "text",
    userSelect: "text",
    display: "inline-block",
    transitionDuration: "0.12s",
    verticalAlign: "middle",
    "&.isLink": {
      cursor: "pointer",
      "&:hover,&:focus": {
        textDecoration: "underline"
      }
    }
  },
  iconBlock: {
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
  link: {
    color: theme.palette.JANGO,
    lineHeight: "80px"
  }
}));

export default function Name({ name, data }) {
  const { thumbnail, type, id, ownerId } = data;
  const isShared = ownerId !== userInfoStore.getUserInfo("id");
  let genericIcon = FileSVG;
  if (type !== "block") {
    if (isShared) {
      genericIcon = FolderSharedSVG;
    } else {
      genericIcon = FolderSVG;
    }
  }
  const classes = useStyles();
  const [isTooltipToShow, setTooltip] = useState(false);
  const widthFixDiv = useRef();
  const nameSpan = useRef();

  const checkTooltip = () => {
    let newTooltip = false;
    if (nameSpan.current.offsetWidth > widthFixDiv.current.offsetWidth) {
      newTooltip = true;
    }
    if (newTooltip !== isTooltipToShow) {
      setTooltip(newTooltip);
    }
  };

  useLayoutEffect(checkTooltip, []);

  useEffect(checkTooltip, [name]);

  const namePart = (isLink = false) => (
    <Typography
      className={clsx(classes.name, isLink ? "isLink" : "")}
      variant="body2"
      data-component="blockName"
      data-text={name}
      component="span"
      ref={nameSpan}
    >
      {name}
    </Typography>
  );
  let nameComponent = (
    <Box ref={widthFixDiv}>
      {type === LIBRARY && id ? (
        <Link to={`/resources/blocks/${id}`} className={classes.link}>
          {namePart(true)}
        </Link>
      ) : (
        namePart(false)
      )}
    </Box>
  );

  if (isTooltipToShow)
    nameComponent = (
      <Tooltip placement="top" title={name}>
        {nameComponent}
      </Tooltip>
    );
  return (
    <Box className={classes.root}>
      <Box className={classes.iconBlock}>
        {type === "block" && thumbnail ? (
          <Thumbnail src={thumbnail} name={name} fileId={id} />
        ) : (
          <img src={genericIcon} alt={name} className={classes.genericIcon} />
        )}
      </Box>
      <Box className={classes.nameBlock}>{nameComponent}</Box>
    </Box>
  );
}

Name.propTypes = {
  name: PropTypes.string.isRequired,
  data: PropTypes.shape({
    thumbnail: PropTypes.string,
    type: PropTypes.oneOf([BLOCK, LIBRARY]),
    id: PropTypes.string,
    ownerId: PropTypes.string
  })
};

Name.defaultProps = {
  data: {
    thumbnail: "",
    type: BLOCK,
    id: ""
  }
};
