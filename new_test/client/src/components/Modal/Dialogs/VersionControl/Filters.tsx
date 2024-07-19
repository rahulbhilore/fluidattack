import { Grid, styled } from "@mui/material";
import { DateTime } from "luxon";
import React, { SyntheticEvent, useCallback, useMemo, useState } from "react";
import { FormattedDate, FormattedMessage } from "react-intl";
import KudoDateRangePicker from "../../../Inputs/KudoDateRangePickerNext/KudoDateRangePicker";
import FilterButton from "./FilterButton";

export enum FilterType {
  All = 0,
  Today = 1,
  Yesterday = 2,
  Week = 3,
  Datepicker = 4
}

type PropType = {
  onFilter: (startTime?: number, endTime?: number) => void;
};

const StyledGrid = styled(Grid)({
  marginBottom: "18px"
});

const StyledHr = styled("hr")(({ theme }) => ({
  borderTop: `2px solid ${theme.palette.REY}`,
  marginTop: "12px"
}));

const StyledDatePickerGrid = styled(Grid)(() => ({
  visibility: "hidden",
  position: "absolute",
  right: "50px",
  top: 0
}));

export default function Filters(props: PropType) {
  const { onFilter } = props;
  const [currentFilter, setCurrentFilter] = useState<FilterType>(
    FilterType.All
  );
  const [datePickerOpen, setDatePickerOpen] = useState(false);
  const [selectedDate, setSelectedDate] = useState<[Date, Date]>([
    new Date(),
    new Date()
  ]);

  const dateRangeFilterTooltip = useMemo(() => {
    const [start, end] = selectedDate;
    if (start?.getTime() !== end?.getTime())
      return (
        <FormattedMessage
          id="shownVersionFromTo"
          values={{
            start: <FormattedDate value={start} />,
            end: <FormattedDate value={end} />
          }}
        />
      );

    return (
      <FormattedMessage
        id="shownFilesFor"
        values={{
          date: <FormattedDate value={start} />
        }}
      />
    );
  }, [selectedDate]);

  const clickHandler = useCallback(
    (_: SyntheticEvent, filterType: FilterType) => {
      setCurrentFilter(filterType);
      setDatePickerOpen(filterType === FilterType.Datepicker);

      if (filterType === FilterType.Datepicker) return;

      switch (filterType as FilterType) {
        case FilterType.All: {
          onFilter();
          break;
        }
        case FilterType.Today: {
          onFilter(DateTime.now().startOf("day").toMillis());
          break;
        }
        case FilterType.Yesterday: {
          onFilter(
            DateTime.now().startOf("day").minus({ days: 1 }).toMillis(),
            DateTime.now().startOf("day").toMillis()
          );
          break;
        }
        case FilterType.Week: {
          onFilter(
            DateTime.now().startOf("day").minus({ days: 7 }).toMillis(),
            DateTime.now().startOf("day").plus({ days: 1 }).toMillis()
          );
          break;
        }
        default: {
          onFilter(undefined);
        }
      }
    },
    [onFilter]
  );

  const datePickerHandler = useCallback(
    (range: [begin: Date, end: Date]) => {
      const [begin, end] = range;
      setDatePickerOpen(false);
      setSelectedDate(range);
      onFilter(
        DateTime.fromJSDate(begin).startOf("day").toMillis(),
        DateTime.fromJSDate(end).startOf("day").plus({ days: 1 }).toMillis()
      );
    },
    [onFilter]
  );

  const handleCloseDatePicker = useCallback(() => setDatePickerOpen(false), []);

  return (
    <StyledGrid item>
      <FilterButton
        active={FilterType.All === currentFilter}
        caption={<FormattedMessage id="all" />}
        onClick={clickHandler}
        tooltip={<FormattedMessage id="showAllVersions" />}
        type={FilterType.All}
      />
      <FilterButton
        active={FilterType.Today === currentFilter}
        caption={<FormattedMessage id="today" />}
        onClick={clickHandler}
        tooltip={
          <FormattedMessage
            id="showVersionFor"
            values={{
              date: <FormattedDate value={Date.now()} />
            }}
          />
        }
        type={FilterType.Today}
      />
      <FilterButton
        active={FilterType.Yesterday === currentFilter}
        caption={<FormattedMessage id="yesterday" />}
        onClick={clickHandler}
        type={FilterType.Yesterday}
        tooltip={
          <FormattedMessage
            id="showVersionFor"
            values={{
              date: (
                <FormattedDate
                  value={DateTime.now()
                    .startOf("day")
                    .minus({ days: 1 })
                    .toMillis()}
                />
              )
            }}
          />
        }
      />
      <FilterButton
        active={FilterType.Week === currentFilter}
        caption={<FormattedMessage id="last_week" />}
        onClick={clickHandler}
        tooltip={
          <FormattedMessage
            id="showVersionFromTo"
            values={{
              start: (
                <FormattedDate
                  value={DateTime.now()
                    .startOf("day")
                    .minus({ days: 7 })
                    .toMillis()}
                />
              ),
              end: (
                <FormattedDate
                  value={DateTime.now().startOf("day").toMillis()}
                />
              )
            }}
          />
        }
        type={FilterType.Week}
      />
      <FilterButton
        caption={<FormattedMessage id="filter_by_date" />}
        datepicker
        onClick={clickHandler}
        type={FilterType.Datepicker}
        tooltip={
          currentFilter === FilterType.Datepicker ? (
            dateRangeFilterTooltip
          ) : (
            <FormattedMessage id="showVersionsForSpecificDate" />
          )
        }
      />
      <StyledDatePickerGrid item>
        <KudoDateRangePicker
          disableFuture
          id="date-picker-inline"
          onChange={datePickerHandler}
          onClose={handleCloseDatePicker}
          open={datePickerOpen}
        />
      </StyledDatePickerGrid>
      <StyledHr />
    </StyledGrid>
  );
}
