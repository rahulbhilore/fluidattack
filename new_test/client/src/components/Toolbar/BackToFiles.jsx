import React from "react";
import PropTypes from "prop-types";
import { Link } from "react-router";
import Isvg from "react-inlinesvg";
import { FormattedMessage } from "react-intl";
import { makeStyles } from "@material-ui/core/styles";
import arrowSvg from "../../assets/images/arrow.svg";

const useStyles = makeStyles(theme => ({
  root: {
    lineHeight: "35px",
    fontSize: theme.typography.pxToRem(12),
    "&:hover, &:focus": {
      textDecoration: "none",
      "& span:not(.isvg)": {
        textDecoration: "underline"
      }
    }
  },
  svg: {
    marginRight: "10px",
    width: "20px",
    height: "20px",
    verticalAlign: "middle"
  }
}));

export default function BackToFiles(props) {
  const classes = useStyles();
  const { to } = props;

  return (
    <Link className={classes.root} to={to}>
      <Isvg
        className={classes.svg}
        cacheRequests
        uniquifyIDs={false}
        src={arrowSvg}
      />
      <FormattedMessage id="myDrawings" />
    </Link>
  );
}

BackToFiles.propTypes = {
  to: PropTypes.func.isRequired
};
