import React from "react";
import PropTypes from "prop-types";
import Typography from "@material-ui/core/Typography";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import StorageIcons from "../../../constants/appConstants/StorageIcons";
import KudoSwitch from "../../Inputs/KudoSwitch/KudoSwitch";
import BlockHeading from "./BlockHeading";

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
const useStyles = makeStyles(theme => ({
  img: {
    maxWidth: "35px",
    maxHeight: "35px",
    marginRight: theme.spacing(2)
  },
  row: {
    height: "36px",
    display: "flex",
    justifyContent: "space-between",
    marginBottom: theme.spacing(2)
  },
  labelHelper: {
    flexGrow: 1,
    alignItems: "center",
    display: "flex"
  },
  storageName: {
    display: "inline",
    color: theme.palette.JANGO,
    fontSize: "12px",
    fontWeight: "bold",
    userSelect: "text"
  }
}));

export default function StorageSettings({ storagesList, companyOptions }) {
  const classes = useStyles();
  return (
    <>
      <BlockHeading messageId="availableStorageTypes" />
      {storagesList.map(storage => {
        const normalizedName = storage.name.toLowerCase();
        // https://graebert.atlassian.net/browse/XENON-30781
        if (normalizedName === "internal") return null;
        return (
          <Grid item xs={12} className={classes.row} key={normalizedName}>
            <div className={classes.labelHelper}>
              <img
                className={classes.img}
                src={StorageIcons[`${storage.name.toLowerCase()}ActiveSVG`]}
                alt={storage.displayName || normalizedName}
              />
              <Typography variant="body1" className={classes.storageName}>
                {storage.displayName}
              </Typography>
            </div>
            <KudoSwitch
              defaultChecked={companyOptions[normalizedName]}
              id={normalizedName}
              name={`storages.${normalizedName}`}
              formId="companyInfo"
              styles={customSwitchStyles}
              dataComponent="settings-switch"
            />
          </Grid>
        );
      })}
    </>
  );
}

StorageSettings.propTypes = {
  storagesList: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired,
      displayName: PropTypes.string.isRequired
    })
  ).isRequired,
  companyOptions: PropTypes.shape({ [PropTypes.string]: PropTypes.bool })
    .isRequired
};
