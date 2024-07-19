import React from "react";
import _ from "underscore";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import DialogIcons from "../../constants/appConstants/DialogIcons";

const useStyles = makeStyles(theme => ({
  icon: {
    minWidth: "20px",
    height: "20px",
    marginRight: theme.spacing(1),
    display: "inline-block",
    verticalAlign: "middle"
  }
}));

export default function DialogIcon({ dialogName }) {
  const classes = useStyles();
  const dialogIcon = DialogIcons(dialogName);
  if (dialogIcon === null) {
    return null;
  }
  const iconList = [];
  if (React.isValidElement(dialogIcon)) {
    return <dialogIcon.type className={classes.icon} />;
  }
  if (_.isObject(dialogIcon)) {
    if (_.isArray(dialogIcon.img)) {
      for (let i = 0; i < dialogIcon.img.length; i += 1) {
        iconList.push(<img alt={dialogName} src={dialogIcon.img[i]} />);
      }
      iconList.push(<span className="feedbackIcon">{dialogIcon.text}</span>);
      return <div className="typeIcon">{iconList}</div>;
    }
  } else if (dialogIcon.includes("svg")) {
    return <img alt={dialogName} src={dialogIcon} className={classes.icon} />;
  } else {
    return <img alt={dialogName} src={dialogIcon} />;
  }
  return null;
}

DialogIcon.propTypes = {
  dialogName: PropTypes.string
};

DialogIcon.defaultProps = {
  dialogName: null
};
