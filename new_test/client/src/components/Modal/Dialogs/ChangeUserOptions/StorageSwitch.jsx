import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import KudoSwitch from "../../../Inputs/KudoSwitch/KudoSwitch";
import StorageIcons from "../../../../constants/appConstants/StorageIcons";

export default function StorageSwitch({
  storageName,
  defaultChecked,
  formId = "userOptionsForm",
  accountInfo = "",
  fullWidth = false,
  onChange = () => null,
  disabled = false
}) {
  return (
    <Grid item xs={12} sm={fullWidth ? 12 : 6}>
      <KudoSwitch
        id={storageName}
        key={`userOptions_${storageName}`}
        formId={formId}
        iconSrc={
          StorageIcons[`${storageName.toLowerCase()}ActiveSVG`] ||
          StorageIcons.samplesInactiveGreySVG
        }
        defaultChecked={defaultChecked}
        name={storageName}
        label={accountInfo || storageName}
        onChange={onChange}
        translateLabel={false}
        disabled={disabled}
        dataComponent={`user-storage-switch-${storageName.toLowerCase()}`}
        styles={{
          icon: {
            marginTop: 0,
            verticalAlign: "middle"
          },
          label: {
            width: "95%",
            "& .MuiTypography-root": {
              fontSize: 12,
              color: "#124daf",
              marginLeft: 5,
              "&::first-letter": {
                textTransform: "uppercase"
              }
            }
          },
          switch: {
            width: "62px",
            height: "36px",
            marginRight: 5,
            "& .MuiSwitch-thumb": {
              width: 22,
              height: 22
            },
            "& .Mui-checked": {
              transform: "translateX(25px)"
            },
            "& .MuiSwitch-switchBase": {
              padding: 1,
              color: "#FFFFFF",
              top: "6px",
              left: "6px"
            }
          }
        }}
      />
    </Grid>
  );
}

StorageSwitch.propTypes = {
  storageName: PropTypes.string.isRequired,
  defaultChecked: PropTypes.bool,
  formId: PropTypes.string,
  accountInfo: PropTypes.string,
  fullWidth: PropTypes.bool,
  onChange: PropTypes.func,
  disabled: PropTypes.bool
};

StorageSwitch.defaultProps = {
  defaultChecked: false,
  formId: "userOptionsForm",
  accountInfo: "",
  fullWidth: false,
  onChange: () => null,
  disabled: false
};
