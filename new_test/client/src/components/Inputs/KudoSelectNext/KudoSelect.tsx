import {
  FormControl,
  MenuItem,
  Select,
  SelectChangeEvent,
  Typography,
  styled
} from "@mui/material";
import React, { useEffect, useMemo, useState } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { KudoSelectProps } from "./types";

const StyledTypography = styled(Typography)(({ theme }) => ({
  fontSize: 12,
  fontWeight: 400,
  marginBottom: theme.spacing(0.5),
  color: theme.palette.font.textField.labelDefault
}));

export default function KudoSelect({
  dataComponent = "select",
  defaultValue,
  fullWidth,
  id,
  label,
  onChange,
  options,
  value: valueProp,
  sort = false,
  ...rest
}: KudoSelectProps) {
  const { formatMessage } = useIntl();
  const [value, setValue] = useState(defaultValue ?? valueProp);

  const handleChange = (e: SelectChangeEvent) => {
    setValue(e.target.value);
    if (onChange) onChange(e, e.target.value);
  };

  const optionKeys = useMemo(() => {
    const keys = Object.keys(options);
    if (sort)
      return keys?.sort?.((x, y) =>
        options[x].toLocaleUpperCase() > options[y].toLocaleUpperCase() ? 1 : -1
      );
    return keys;
  }, [options, sort]);

  useEffect(() => {
    setValue(valueProp);
  }, [valueProp]);

  return (
    <FormControl size="small" fullWidth={fullWidth}>
      <StyledTypography>
        <FormattedMessage id={label} />
      </StyledTypography>
      <Select
        {...rest}
        data-component={dataComponent}
        displayEmpty
        id={id}
        inputProps={{ "aria-label": formatMessage({ id: label }) }}
        onChange={handleChange}
        value={value}
      >
        {optionKeys.map(key => (
          <MenuItem key={key} value={key}>
            {options[key]}
          </MenuItem>
        ))}
      </Select>
    </FormControl>
  );
}
