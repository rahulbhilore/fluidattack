import { DatePickerProps, PickersDayProps } from "@mui/x-date-pickers";

type KudoDateRangePickerProp = {
  id?: string;
  onChange: (range: [Date, Date]) => void;
} & Omit<DatePickerProps<Date>, "onChange">;

type DaySlotProp = {
  begin: Date;
  end: Date;
  handleChangeDay: (day: Date) => void;
  hover: Date;
  onClose: () => void;
  setHover: (date: Date | undefined) => void;
} & PickersDayProps<Date>;

export { DaySlotProp, KudoDateRangePickerProp };
