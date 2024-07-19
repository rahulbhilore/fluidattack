import React from "react";
import PropTypes from "prop-types";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import SVG from "react-inlinesvg";
import clsx from "clsx";
import MainFunctions from "../../../libraries/MainFunctions";
import Loading from "./Loading";
import ChevronUpSVG from "../../../assets/images/Chevron-Up.svg";
import ChevronDownSVG from "../../../assets/images/Chevron-Down.svg";
import Icon from "./Icon";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: "white",
    border: "none",
    borderBottom: "solid 1px #ececec",
    width: "100%",
    cursor: "pointer",
    lineHeight: "36px",
    height: "36px",
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    textAlign: "left",
    paddingTop: 0,
    paddingBottom: 0,
    "&.selected": {
      backgroundColor: theme.palette.OBI,
      color: theme.palette.LIGHT
    },
    "&.unavailable": {
      cursor: "not-allowed",
      pointerEvents: "none"
    }
  },
  caption: {
    color: theme.palette.DARK,
    display: "inline-block",
    marginLeft: "5px",
    lineHeight: "36px",
    verticalAlign: "top",
    maxWidth: "90%",
    textOverflow: "ellipsis",
    overflow: "hidden",
    whiteSpace: "nowrap",
    "&.selected": {
      fontWeight: "bold",
      color: theme.palette.LIGHT
    },
    "&.unavailable": {
      color: theme.palette.REY
    }
  },
  collapseIcon: {
    width: "20px",
    display: "inline-block",
    float: "right",
    marginTop: "8px",
    "& > polygon": {
      fill: `${theme.palette.SNOKE} !important`
    },
    "&.selected > polygon": {
      fill: `${theme.palette.LIGHT} !important`
    }
  },
  block: {
    display: "flex",
    alignItems: "center"
  }
}));

export default function ItemLabel({
  toggleFolded,
  classNames,
  icon,
  objectType,
  objectId,
  objectName,
  isSelected,
  isLoading,
  isInside,
  isChecked,
  isUnfolded,
  isAbleToMove,
  mimeType
}) {
  const { onKeyDown } = MainFunctions.getA11yHandler(toggleFolded);
  const classes = useStyles();
  return (
    <button
      type="button"
      onClick={toggleFolded}
      onKeyDown={onKeyDown}
      className={clsx(classNames, classes.root, isSelected ? "selected" : "")}
      data-component="tree-object-name"
      data-text={objectName}
      data-type={objectType}
    >
      <div className={classes.block}>
        <Icon
          url={icon}
          id={objectId}
          name={objectName}
          type={objectType}
          mimeType={mimeType}
          isAbleToMove={isAbleToMove}
          isSelected={isSelected}
        />{" "}
        <Typography
          variant="body2"
          className={clsx(
            classes.caption,
            isSelected ? "selected" : "",
            !isAbleToMove ? "unavailable" : ""
          )}
        >
          {objectName}
        </Typography>
      </div>
      <div className={classes.block}>
        {isLoading ? <Loading /> : null}
        {isInside || !isChecked ? (
          <SVG
            src={isUnfolded ? ChevronUpSVG : ChevronDownSVG}
            className={clsx(classes.collapseIcon, isSelected ? "selected" : "")}
          />
        ) : null}
      </div>
    </button>
  );
}

ItemLabel.propTypes = {
  toggleFolded: PropTypes.func.isRequired,
  classNames: PropTypes.string,
  icon: PropTypes.string,
  objectType: PropTypes.string.isRequired,
  objectId: PropTypes.string.isRequired,
  objectName: PropTypes.string.isRequired,
  isSelected: PropTypes.bool,
  isLoading: PropTypes.bool,
  isInside: PropTypes.bool,
  isChecked: PropTypes.bool,
  isUnfolded: PropTypes.bool,
  isAbleToMove: PropTypes.bool,
  mimeType: PropTypes.string
};

ItemLabel.defaultProps = {
  classNames: "",
  icon: "",
  isSelected: false,
  isLoading: false,
  isInside: false,
  isChecked: false,
  isUnfolded: false,
  isAbleToMove: true,
  mimeType: ""
};
