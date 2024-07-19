import React from "react";
import PropTypes from "prop-types";
import Typography from "@material-ui/core/Typography";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import KudoInput from "../../../Inputs/KudoInput/KudoInput";
import Block from "./Block";
import * as InputTypes from "../../../../constants/appConstants/InputTypes";

const useStyles = makeStyles(theme => ({
  label: {
    color: theme.palette.OBI,
    lineHeight: "36px",
    fontSize: theme.typography.pxToRem(12)
  },
  usageLabel: {
    lineHeight: "36px",
    textAlign: "right",
    cursor: "text",
    userSelect: "text",
    display: "inline-block",
    fontSize: theme.typography.pxToRem(12),
    marginRight: "5px"
  },
  percentage: {
    lineHeight: "36px",
    fontWeight: "bold",
    display: "inline-block",
    fontSize: theme.typography.pxToRem(12),
    marginRight: "5px"
  },
  input: {
    maxWidth: "100px"
  }
}));

export default function UsageAndQuota({ quota, usage }) {
  const classes = useStyles();
  const validationFunction = v => parseInt(v, 10) >= usage;
  const percentage = Math.ceil((usage / quota) * 100);
  return (
    <Block>
      <Grid item xs={12} sm={4}>
        <Typography variant="body2" className={classes.label}>
          <FormattedMessage id="usageAndQuota" />
        </Typography>
      </Grid>
      <Grid item xs={12} sm={3}>
        <Typography
          variant="body2"
          color="primary"
          className={classes.percentage}
        >
          {`${percentage}%`}
        </Typography>
        <Typography
          variant="body2"
          color="primary"
          className={classes.usageLabel}
        >
          {`${usage}/`}
        </Typography>
      </Grid>
      <Grid item xs={12} sm={5}>
        <KudoInput
          id="quota"
          name="quota"
          type={InputTypes.NUMBER}
          min={1}
          isDefaultValueValid
          validationFunction={validationFunction}
          className={classes.input}
          formId="userOptionsForm"
          defaultValue={quota}
          inputDataComponent="quota-input"
        />
      </Grid>
    </Block>
  );
}

UsageAndQuota.propTypes = {
  usage: PropTypes.number,
  quota: PropTypes.number
};

UsageAndQuota.defaultProps = {
  usage: 0,
  quota: 0
};
