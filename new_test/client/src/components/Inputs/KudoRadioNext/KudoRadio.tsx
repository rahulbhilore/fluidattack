import {
  Box,
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  styled,
  typographyClasses
} from "@mui/material";
import React, { ChangeEvent, useEffect, useState } from "react";
import { useIntl } from "react-intl";
import { KudoRadioProps } from "./types";

const StyledIcon = styled(Box)(({ theme }) => ({
  alignItems: "center",
  background: theme.palette.radiobutton.unactive.standard,
  borderRadius: "50%",
  display: "flex",
  height: 20,
  justifyContent: "center",
  width: 20
}));

const StyledCheckedIcon = styled(StyledIcon)(({ theme }) => ({
  background: theme.palette.radiobutton.active.standard,
  "&::before": {
    background: theme.palette.radiobutton.checkedIconBgColor,
    borderRadius: "50%",
    content: `""`,
    display: "block",
    height: 8,
    width: 8
  }
}));

export default function KudoRadio({
  dataComponent,
  defaultValue,
  disabled,
  disabledOptions,
  label,
  onChange,
  options,
  sx,
  value,
  ...others
}: KudoRadioProps) {
  const { formatMessage } = useIntl();
  const [selectedValue, setSelectedValue] = useState(defaultValue ?? value);

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    setSelectedValue(e?.target?.value);
    if (onChange) onChange(e, e?.target?.value);
  };

  useEffect(() => {
    setSelectedValue(value);
  }, [value]);

  return (
    <FormControl disabled={disabled} sx={{ rowGap: 2.5 }}>
      <FormLabel
        sx={{
          color: theme => theme.palette.header,
          fontSize: 14,
          fontWeight: 600
        }}
      >
        {formatMessage({ id: label })}
      </FormLabel>
      <RadioGroup
        data-component={dataComponent}
        onChange={handleChange}
        value={selectedValue}
        {...others}
        sx={{ rowGap: 2.5, ...sx }}
      >
        {options.map(({ value: itemValue, label: itemLabel }) => (
          <FormControlLabel
            control={
              <Radio
                checkedIcon={<StyledCheckedIcon />}
                icon={<StyledIcon />}
              />
            }
            disabled={disabledOptions?.includes(itemValue)}
            key={itemValue}
            label={itemLabel}
            value={itemValue}
            sx={{
              mx: 0,
              [`& .${typographyClasses.root}`]: { pl: 1 },
              background: theme =>
                value === itemValue
                  ? theme.palette.checkboxes.row.active
                  : "inherit"
            }}
          />
        ))}
      </RadioGroup>
    </FormControl>
  );
}
