import { IconButton, Typography } from "@mui/material";
import {
  BaseSingleInputFieldProps,
  DateValidationError,
  FieldSection,
  UseDateFieldProps
} from "@mui/x-date-pickers";
import React from "react";
import { FormattedMessage } from "react-intl";
import { ReactSVG } from "react-svg";
import calendarTodayIconSVG from "../../../../../../assets/images/dialogs/icons/calendarTodayIconSVG.svg";

interface ButtonFieldProps
  extends UseDateFieldProps<Date>,
    BaseSingleInputFieldProps<
      Date | null,
      Date,
      FieldSection,
      DateValidationError
    > {
  onClick: () => void;
}

export default function CustomDatePickerField({
  id,
  InputProps: { ref } = {},
  onClick
}: ButtonFieldProps) {
  return (
    <>
      <IconButton
        onClick={onClick}
        id={id}
        ref={ref}
        sx={{
          pl: 0,
          "&:hover": { background: "unset" },
          "& .react-svg-icon > div": { display: "flex" }
        }}
      >
        <ReactSVG src={calendarTodayIconSVG} className="react-svg-icon" />
      </IconButton>
      <Typography>
        <FormattedMessage id="setDifferentExpirationDate" />
      </Typography>
    </>
  );
}
