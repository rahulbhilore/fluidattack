import { Checkbox, FormControlLabel } from "@mui/material";
import React from "react";
import { useIntl } from "react-intl";
import { KudoCheckboxProps } from "./types";

export default function KudoCheckBox({
  bgColorHighlighted = false,
  checked,
  checkboxSx,
  color,
  disabled,
  label,
  labelPlacement,
  onChange,
  required,
  sx,
  translateLabel = false,
  ...others
}: KudoCheckboxProps) {
  const { formatMessage } = useIntl();
  return (
    <FormControlLabel
      checked={checked}
      color={color}
      componentsProps={{ typography: { variant: "body2" } }}
      disabled={disabled}
      label={translateLabel ? formatMessage({ id: label }) : label}
      labelPlacement={labelPlacement}
      onChange={onChange}
      required={required}
      sx={{
        ...sx,
        ...(bgColorHighlighted && checked
          ? { background: ({ palette }) => palette.checkboxes.row.active }
          : {}),
        height: 32,
        paddingRight: 1,
        width: "100%"
      }}
      control={<Checkbox disableRipple {...others} sx={checkboxSx} />}
    />
  );
}
