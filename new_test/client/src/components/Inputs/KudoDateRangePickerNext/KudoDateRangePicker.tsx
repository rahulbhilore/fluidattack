import { DatePicker } from "@mui/x-date-pickers";
import React, { useEffect, useState } from "react";
import DaySlot from "./DaySlot";
import { KudoDateRangePickerProp } from "./types";

export default function KudoDateRangePicker(props: KudoDateRangePickerProp) {
  const { open, onChange, onClose, ...others } = props;
  const [begin, setBegin] = useState<Date>();
  const [end, setEnd] = useState<Date>();
  const [hover, setHover] = useState<Date>();

  const handleChangeDay = (day: Date) => {
    if (!open) return;

    if (!day) return;
    if (!begin) setBegin(day);
    else if (!end) {
      setEnd(day);
      onChange(
        [begin, day].sort(
          (date1, date2) => date1.getTime() - date2.getTime()
        ) as [Date, Date]
      );
    } else {
      setBegin(day);
      setEnd(undefined);
    }
  };

  useEffect(() => {
    if (end && onClose) onClose();
  }, [end, onClose]);

  return (
    <DatePicker
      closeOnSelect={false}
      onChange={() => {}}
      onClose={onClose}
      open={open}
      slots={{ day: DaySlot }}
      slotProps={{
        day: {
          begin,
          end,
          handleChangeDay,
          hover,
          onClose,
          open,
          setHover
        } as never
        /**
         * BD: Aliased as never because of passing non-existing props to slots issue is pending from MUI side
         * https://github.com/mui/mui-x/issues/9775
         */
      }}
      {...others}
    />
  );
}
