import React from "react";
import PropTypes from "prop-types";
import Block from "./Block";
import KudoSwitch from "../../../Inputs/KudoSwitch/KudoSwitch";

export default function SwitchBlock({
  id,
  name,
  label,
  defaultChecked,
  formId = "userOptionsForm",
  onChange = () => null,
  dataComponent = "change-options-switch"
}) {
  return (
    <Block>
      <KudoSwitch
        id={id}
        defaultChecked={defaultChecked}
        name={name}
        label={label}
        formId={formId}
        onChange={onChange}
        dataComponent={dataComponent}
        styles={{
          icon: {
            marginTop: 0
          },
          label: {
            width: "100%",
            "& .MuiTypography-root": {
              fontSize: 12,
              color: "#124daf",
              marginLeft: -4,
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
    </Block>
  );
}

SwitchBlock.propTypes = {
  id: PropTypes.string,
  name: PropTypes.string,
  label: PropTypes.string,
  formId: PropTypes.string,
  defaultChecked: PropTypes.bool,
  onChange: PropTypes.func,
  dataComponent: PropTypes.string
};

SwitchBlock.defaultProps = {
  id: "",
  name: "",
  label: "",
  defaultChecked: false,
  formId: "userOptionsForm",
  onChange: () => null,
  dataComponent: "change-options-switch"
};
