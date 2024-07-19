import { Button, SxProps, Theme, Tooltip } from "@mui/material";
import React, { ReactNode, SyntheticEvent, useCallback, useMemo } from "react";
import { FilterType } from "./Filters";

type PropType = {
  active?: boolean;
  caption: ReactNode;
  datepicker?: boolean;
  onClick: (e: SyntheticEvent, type: FilterType) => void;
  tooltip?: ReactNode;
  type: FilterType;
};

export default function FilterButton(props: PropType) {
  const {
    active = false,
    caption,
    datepicker = false,
    onClick,
    tooltip = "",
    type
  } = props;

  const activeSx = useMemo<SxProps<Theme>>(
    () => ({
      backgroundColor: theme => theme.palette.OBI,
      color: theme => theme.palette.LIGHT,
      textTransform: "uppercase",
      "&:hover": {
        backgroundColor: theme => theme.palette.OBI,
        color: theme => theme.palette.LIGHT,
        cursor: datepicker ? "pointer" : "default"
      }
    }),
    [datepicker]
  );

  const datePickerSx = useMemo<SxProps<Theme>>(
    () => ({
      float: "right",
      margin: 0
    }),
    []
  );

  const buttonSx = useMemo(
    () =>
      ({
        color: (theme: Theme) => theme.palette.OBI,
        border: (theme: Theme) => `1px solid ${theme.palette.OBI}`,
        py: 0.25,
        px: 1,
        minWidth: 0,
        textTransform: "uppercase",
        borderRadius: 0,
        marginRight: 1,
        "&:hover": {
          background: "initial"
        },
        ...(active && !datepicker ? activeSx : {}),
        ...(datepicker ? { ...datePickerSx, ...activeSx } : {})
      }) as SxProps<Theme>,
    [datepicker, active, datePickerSx, activeSx]
  );

  const clickHandler = useCallback(
    (e: SyntheticEvent) => {
      onClick(e, type);
    },
    [onClick]
  );

  return (
    <Tooltip placement="bottom" title={tooltip}>
      <Button sx={buttonSx} onClick={clickHandler}>
        {caption}
      </Button>
    </Tooltip>
  );
}
