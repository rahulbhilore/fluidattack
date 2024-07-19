import React from "react";
import PropTypes from "prop-types";
import { Grid } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import KudoSwitch from "../../../../Inputs/KudoSwitch/KudoSwitch";

const useStyles = makeStyles(theme => ({
  root: {
    marginBottom: theme.spacing(1)
  }
}));

export default function ExportToggle({
  changeExportState,
  isExport,
  disabled
}) {
  const classes = useStyles();
  return (
    <Grid item xs={12} className={classes.root}>
      <KudoSwitch
        name="enableExport"
        id="enableExport"
        label="exportToPDF"
        defaultChecked={isExport}
        disabled={disabled}
        onChange={changeExportState}
        dataComponent="enable-export-switch"
        styles={{
          formGroup: {
            margin: 0
          },
          label: {
            width: "100%",
            margin: "0 !important",
            "& .MuiTypography-root": {
              fontSize: 12
            }
          },
          switch: {
            width: "58px",
            height: "32px",
            margin: "0 !important",
            "& .MuiSwitch-thumb": {
              width: 20,
              height: 20
            },
            "& .Mui-checked": {
              transform: "translateX(23px)"
            },
            "& .MuiSwitch-switchBase": {
              padding: 1,
              color: "#FFFFFF",
              top: "5px",
              left: "6px"
            }
          }
        }}
      />
    </Grid>
  );
}

ExportToggle.propTypes = {
  changeExportState: PropTypes.func.isRequired,
  isExport: PropTypes.bool,
  disabled: PropTypes.bool
};

ExportToggle.defaultProps = {
  isExport: false,
  disabled: true
};
