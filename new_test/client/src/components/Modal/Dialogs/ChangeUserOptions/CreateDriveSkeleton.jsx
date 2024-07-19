import React from "react";
import PropTypes from "prop-types";
import Typography from "@material-ui/core/Typography";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import StorageIcons from "../../../../constants/appConstants/StorageIcons";
import KudoButton from "../../../Inputs/KudoButton/KudoButton";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import AdminActions from "../../../../actions/AdminActions";
import Block from "./Block";

const useStyles = makeStyles(theme => ({
  label: {
    color: theme.palette.OBI,
    lineHeight: "36px",
    display: "inline-block",
    fontSize: theme.typography.pxToRem(12)
  },
  usageLabel: {
    lineHeight: "36px",
    textAlign: "right",
    cursor: "text",
    userSelect: "text",
    display: "inline-block"
  },
  percentage: {
    lineHeight: "36px",
    fontWeight: "bold",
    display: "inline-block"
  },
  button: {
    fontSize: theme.typography.pxToRem(12)
  },
  input: {
    width: "80%"
  },
  icon: {
    height: "20px",
    width: "20px"
  },
  buttonBlock: {
    textAlign: "right"
  }
}));

export default function CreateDriveSkeleton({ userId, areSamplesCreated }) {
  const classes = useStyles();
  const triggerSkeletonCreation = () => {
    AdminActions.triggerSkeletonCreation(userId, areSamplesCreated);
  };
  const button = (
    <KudoButton
      isDisabled={false}
      onClick={triggerSkeletonCreation}
      styles={{ typography: classes.button }}
      dataComponent="createSkeletonFiles"
    >
      {areSamplesCreated ? "Force creation" : "Create"}
    </KudoButton>
  );
  return (
    <Block>
      <Grid item xs={12} sm={8} md={7}>
        <img
          src={StorageIcons.samplesActiveSVG}
          alt="ARES Kudo Drive"
          className={classes.icon}
        />
        <Typography variant="body2" className={classes.label}>
          Create ARES Kudo Drive samples
        </Typography>
      </Grid>
      <Grid item xs={12} sm={4} md={5} className={classes.buttonBlock}>
        {areSamplesCreated ? (
          <SmartTooltip
            placement="top"
            title="Samples have been already created. Clicking will enforce."
          >
            {button}
          </SmartTooltip>
        ) : (
          button
        )}
      </Grid>
    </Block>
  );
}

CreateDriveSkeleton.propTypes = {
  userId: PropTypes.string.isRequired,
  areSamplesCreated: PropTypes.bool
};

CreateDriveSkeleton.defaultProps = {
  areSamplesCreated: false
};
