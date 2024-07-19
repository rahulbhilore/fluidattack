import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import { useTheme, makeStyles } from "@material-ui/core/styles";
import { FormattedMessage, FormattedDate, useIntl } from "react-intl";
import { DatePicker } from "@mui/x-date-pickers/DatePicker";
import _ from "underscore";
import { DateTime } from "luxon";
import clsx from "clsx";
import { IconButton, Tooltip } from "@mui/material";
import InfoOutlined from "@mui/icons-material/InfoOutlined";
import KudoRadio from "../../../../Inputs/KudoRadio/KudoRadio";
import UserInfoStore from "../../../../../stores/UserInfoStore";

const msInDay = 24 * 60 * 60 * 1000;
const days30 = DateTime.now().plus({ days: 30 }).endOf("day").toMillis();

const NEVER_TIME = "0";

const useStyles = makeStyles(theme => ({
  root: {
    marginBottom: 10,
    padding: 0
  },
  label: {
    // @ts-ignore
    color: theme.palette.DARK,
    fontSize: theme.typography.pxToRem(12),
    lineHeight: "36px",
    margin: 0,
    fontWeight: "normal",
    "&.disabled": {
      // @ts-ignore
      color: theme.palette.REY
    }
  },
  expirationDate: {
    fontSize: theme.typography.pxToRem(12),
    "&.disabled": {
      // @ts-ignore
      color: theme.palette.REY
    },
    "&.expired": {
      // @ts-ignore
      color: theme.palette.KYLO
    }
  },
  dateButtonBlock: {
    lineHeight: "36px",
    float: "right"
  },
  dateButton: {
    background: "transparent",
    padding: 0,
    textDecoration: "underline",
    border: "0",
    fontSize: theme.typography.pxToRem(12),
    cursor: "pointer",
    "&[disabled]": {
      // @ts-ignore
      color: theme.palette.REY,
      cursor: "default"
    }
  },
  datePicker: {
    visibility: "hidden",
    position: "absolute",
    right: 0,
    top: 0
  },
  radioGroup: {
    marginTop: "2px",
    float: "right"
  }
}));

type ExpirationBlockProps = {
  linkEndTime: Date | null;
  setExpirationTime: (val: Date) => void;
  disabled: boolean;
};

function ExpirationBlock({
  linkEndTime,
  setExpirationTime,
  disabled
}: ExpirationBlockProps) {
  const [isDateSelectOpen, setDateSelect] = React.useState(false);
  const [dateValue, setDateValue] = React.useState<string>(
    linkEndTime !== null ? linkEndTime.getTime().toString() : "-1"
  );
  const theme = useTheme();
  const intl = useIntl();

  const { formatMessage } = intl;
  const expirationDateValues = {
    [NEVER_TIME]: formatMessage({ id: "never" }),
    [days30]: formatMessage(
      {
        id: "NDays"
      },
      { amount: 30 }
    )
  };

  const toggleDatePicker = React.useCallback(() => {
    setDateSelect(ds => !ds);
  }, []);

  const showDatePicker = React.useCallback(() => {
    setDateSelect(true);
  }, []);

  const hideDatePicker = React.useCallback(() => {
    setDateSelect(false);
  }, []);

  const setExpirationTimeFunc = React.useCallback((newDate: Date) => {
    const is0 = newDate.getTime() === 0;
    let endOfDayDate = newDate;
    if (!is0) {
      endOfDayDate = DateTime.fromJSDate(newDate).endOf("day").toJSDate();
    }
    setExpirationTime(endOfDayDate);
    hideDatePicker();
    const time = endOfDayDate.getTime();
    setDateValue(
      Object.prototype.hasOwnProperty.call(expirationDateValues, time)
        ? time.toString()
        : "-1"
    );
  }, []);

  const handleExpirationDateRadioChange = React.useCallback(
    (newDateValue: string) => {
      setExpirationTimeFunc(new Date(parseInt(newDateValue, 10)));
    },
    []
  );

  const { plMaxNumberOfDays } = UserInfoStore.getUserInfo("options");
  const maxDate: Date | undefined =
    plMaxNumberOfDays && _.isNumber(plMaxNumberOfDays) && plMaxNumberOfDays >= 1
      ? new Date(Date.now() + plMaxNumberOfDays * msInDay)
      : undefined;
  const initialFocusedDate: Date = new Date(
    linkEndTime || maxDate || Date.now() + 30 * msInDay
  );
  const disabledOptions = [];
  if (plMaxNumberOfDays < 30) {
    disabledOptions.push(days30.toString());
  }
  const isLinkEndTimeSet = linkEndTime && linkEndTime?.getTime() > 0;
  const isExpired =
    linkEndTime && linkEndTime?.getTime() < Date.now() && isLinkEndTimeSet;
  const classes = useStyles();

  let currentPickerValue = linkEndTime;
  if (linkEndTime === null || linkEndTime.getTime() === 0) {
    if (initialFocusedDate.getTime() > Date.now()) {
      currentPickerValue = initialFocusedDate;
    } else {
      currentPickerValue = maxDate || null;
    }
  }
  return (
    <Grid item xs={12} className={classes.root}>
      <label
        htmlFor="dateToggle"
        className={clsx(classes.label, disabled ? "disabled" : "")}
      >
        <FormattedMessage
          id={isLinkEndTimeSet || isExpired ? "expiresOn" : "expiresIn"}
        />{" "}
        <span
          className={clsx(
            classes.expirationDate,
            disabled ? "disabled" : "",
            !disabled && isExpired ? "expired" : ""
          )}
        >
          {isLinkEndTimeSet ? <FormattedDate value={linkEndTime} /> : null}
          {!disabled && isExpired && (
            <Tooltip
              title={formatMessage({
                id: "linkExpiredUpdateExpirationDateToContinueSharing"
              })}
              placement="top"
            >
              <IconButton>
                <InfoOutlined
                  sx={{ color: "#B82115", height: "17px", width: "17px" }}
                />
              </IconButton>
            </Tooltip>
          )}
        </span>
      </label>
      <div className={classes.dateButtonBlock}>
        <button
          type="button"
          className={classes.dateButton}
          onClick={toggleDatePicker}
          disabled={disabled}
          aria-label={formatMessage({ id: "setDifferentExpirationDate" })}
        >
          <FormattedMessage id="setDifferentExpirationDate" />
        </button>
      </div>
      <div className={classes.datePicker}>
        <DatePicker
          value={currentPickerValue}
          open={isDateSelectOpen}
          onOpen={showDatePicker}
          onClose={hideDatePicker}
          onChange={setExpirationTimeFunc}
          disablePast
          maxDate={maxDate}
        />
      </div>
      <div className={classes.radioGroup}>
        <KudoRadio
          inline
          id="expiryDateRadio"
          name="expiryDateRadio"
          onChange={handleExpirationDateRadioChange}
          options={expirationDateValues}
          disabledOptions={disabledOptions}
          disabled={disabled}
          value={dateValue}
          dataComponent="expiration-radio"
          optionLabelStyles={{
            // @ts-ignore
            color: !disabled ? theme.palette.DARK : theme.palette.REY,
            fontSize: "12px",
            ".Mui-disabled &": {
              // @ts-ignore
              color: theme.palette.REY
            }
          }}
        />
      </div>
    </Grid>
  );
}

ExpirationBlock.propTypes = {
  linkEndTime: PropTypes.number,
  setExpirationTime: PropTypes.func.isRequired,
  disabled: PropTypes.bool
};

ExpirationBlock.defaultProps = {
  linkEndTime: 0,
  disabled: true
};

export default React.memo(ExpirationBlock);
