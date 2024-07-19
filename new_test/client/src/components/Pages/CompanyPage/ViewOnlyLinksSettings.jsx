import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import BlockHeading from "./BlockHeading";
import KudoSwitch from "../../Inputs/KudoSwitch/KudoSwitch";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import * as InputTypes from "../../../constants/appConstants/InputTypes";

const numberOfDaysValidationFunction = value =>
  InputValidationFunctions.isNumeric(value) && value >= 1;

const customSwitchStyles = {
  formGroup: {
    display: "inline",
    float: "right",
    width: "50px"
  },
  switch: {
    width: "59px",
    height: "36px",
    margin: "8px 0 8px 8px",
    "& .MuiSwitch-thumb": {
      width: 22,
      height: 22
    },
    "& .Mui-checked": {
      transform: "translateX(22px)"
    },
    "& .MuiSwitch-switchBase": {
      padding: 1,
      color: "#FFFFFF",
      top: "6px",
      left: "6px"
    }
  }
};

const useStyle = makeStyles(theme => ({
  row: {
    height: "36px",
    marginBottom: theme.spacing(2)
  },
  daysInput: {
    float: "right !important",
    marginBottom: theme.spacing(2),
    width: "60px !important",
    "& .MuiInputBase-input": {
      color: theme.palette.CLONE
    }
  },
  inputFormGroup: {
    display: "inline"
  },
  label: {
    color: theme.palette.JANGO,
    fontSize: ".75rem"
  }
}));

export default function ViewOnlyLinksSettings({
  arePublicLinksAllowed,
  savePublicLinksFlag,
  companyOptions
}) {
  const classes = useStyle();
  return (
    <>
      <BlockHeading messageId="sharingLinks" />
      <Grid item xs={12} className={classes.row}>
        <span className={classes.label}>
          <FormattedMessage id="usageOfSharingLinks" />
        </span>
        <KudoSwitch
          defaultChecked={arePublicLinksAllowed}
          id="sharedLinks"
          name="sharedLinks"
          formId="companyInfo"
          onChange={savePublicLinksFlag}
          styles={customSwitchStyles}
          dataComponent="view-only-switch"
        />
      </Grid>
      <Grid item xs={12} className={classes.row}>
        <span className={classes.label}>
          <FormattedMessage
            id="exportForDrawingsSharedBySharingLink"
            values={{ strong: IntlTagValues.strong }}
          />
        </span>
        <KudoSwitch
          defaultChecked={companyOptions.export}
          id="export"
          formId="companyInfo"
          name="export"
          disabled={!arePublicLinksAllowed}
          styles={customSwitchStyles}
          dataComponent="export-switch"
        />
      </Grid>
      <Grid item xs={12} className={classes.row}>
        <span className={classes.label}>
          <FormattedMessage id="maximumNumberOfDays" />
        </span>
        <KudoInput
          defaultValue={companyOptions.plMaxNumberOfDays || 30}
          isDefaultValueValid
          type={InputTypes.NUMBER}
          classes={{
            formGroup: classes.inputFormGroup,
            input: classes.daysInput
          }}
          min={1}
          id="plMaxNumberOfDays"
          formId="companyInfo"
          name="plMaxNumberOfDays"
          disabled={!arePublicLinksAllowed}
          validationFunction={numberOfDaysValidationFunction}
          inputDataComponent="view-only-link-input"
        />
      </Grid>
    </>
  );
}

ViewOnlyLinksSettings.propTypes = {
  companyOptions: PropTypes.shape({
    plMaxNumberOfDays: PropTypes.number,
    export: PropTypes.bool
  }).isRequired,
  arePublicLinksAllowed: PropTypes.bool,
  savePublicLinksFlag: PropTypes.func.isRequired
};

ViewOnlyLinksSettings.defaultProps = {
  arePublicLinksAllowed: false
};
