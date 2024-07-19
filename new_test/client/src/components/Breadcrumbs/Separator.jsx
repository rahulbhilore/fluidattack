import React from "react";
import makeStyles from "@material-ui/core/styles/makeStyles";
import chevronRightSVG from "../../assets/images/Chevron-Right.svg";

const useStyles = makeStyles(() => ({
  root: {
    width: 20,
    height: 20
  }
}));

export default function Separator() {
  const classes = useStyles();
  return <img src={chevronRightSVG} alt="separator" className={classes.root} />;
}
