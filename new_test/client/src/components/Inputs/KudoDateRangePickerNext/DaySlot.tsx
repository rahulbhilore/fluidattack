import { MuiPickersContext } from "@material-ui/pickers";
import { SxProps, useTheme } from "@mui/material";
import { PickersDay } from "@mui/x-date-pickers";
import React, { useContext, useMemo } from "react";
import { DaySlotProp } from "./types";

export default function DaySlot(props: DaySlotProp) {
  const {
    begin,
    day,
    end,
    handleChangeDay,
    hover,
    onClose,
    setHover,
    ...others
  } = props;

  const theme = useTheme();
  const utils = useContext(MuiPickersContext);
  const min = new Date(
    Math.min(begin?.getTime(), end?.getTime() || hover?.getTime())
  );
  const max = new Date(
    Math.max(begin?.getTime(), end?.getTime() || hover?.getTime())
  );
  const isBetweenRange = day >= min && day <= max && min !== max;
  const isBegin = utils?.isSameDay(day, min) && min !== max;
  const isEnd = utils?.isSameDay(day, max) && min !== max;
  const isBeginSameWithEnd = Boolean(begin) && Boolean(end);

  const betweenRangeSx = useMemo<SxProps>(
    () => ({
      borderRadius: 0,
      borderTop: `2px dashed ${theme.palette.OBI}`,
      borderBottom: `2px dashed ${theme.palette.OBI}`
    }),
    [theme]
  );
  const beginSx = useMemo<SxProps>(
    () => ({
      borderBottomLeftRadius: "50% !important",
      borderTopLeftRadius: "50% !important",
      backgroundColor: `${theme.palette.OBI} !important`,
      color: `${theme.palette.LIGHT}`
    }),
    [theme]
  );
  const endSx = useMemo<SxProps>(
    () => ({
      borderBottomRightRadius: "50% !important",
      borderTopRightRadius: "50% !important",
      backgroundColor: `${theme.palette.OBI} !important`,
      color: `${theme.palette.LIGHT} !important`,
      "&:hover": {
        backgroundColor: `${theme.palette.OBI} !important`
      }
    }),
    [theme]
  );

  const handleMouseEnter = (_: never, _day: Date) => {
    setHover(_day);
  };
  const handleClick = () => {
    handleChangeDay(day);
  };

  return (
    <PickersDay
      {...others}
      onMouseEnter={handleMouseEnter}
      day={day}
      onClick={handleClick}
      sx={
        {
          ...(isBegin ? beginSx : {}),
          ...(isBetweenRange ? betweenRangeSx : {}),
          ...(isEnd ? endSx : {}),
          ...(isBeginSameWithEnd
            ? {
                "&:hover": {
                  backgroundColor: `${theme.palette.OBI} !important`,
                  color: theme.palette.LIGHT
                }
              }
            : {})
        } as SxProps
      }
    />
  );
}
