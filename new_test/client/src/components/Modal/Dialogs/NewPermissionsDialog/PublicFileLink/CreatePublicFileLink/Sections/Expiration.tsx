import InfoOutlined from "@mui/icons-material/InfoOutlined";
import {
  FormControl,
  FormControlLabel,
  IconButton,
  Radio,
  RadioGroup,
  Stack,
  Theme,
  Tooltip,
  Typography,
  radioClasses,
  styled
} from "@mui/material";
import { makeStyles } from "@mui/styles";
import { DatePicker } from "@mui/x-date-pickers";
import clsx from "clsx";
import { DateTime } from "luxon";
import React, { ChangeEvent, useCallback, useContext, useState } from "react";
import { FormattedDate, FormattedMessage, useIntl } from "react-intl";
import _ from "underscore";
import UserInfoStore from "../../../../../../../stores/UserInfoStore";
import { PermissionsDialogContext } from "../../../PermissionsDialogContext";
import CustomDatePickerField from "../CustomDatePickerField";

const days30 = DateTime.now().plus({ days: 30 }).endOf("day").toMillis();
const msInDay = 24 * 60 * 60 * 1000;
const NEVER_TIME = "0";

const useStyles = makeStyles((theme: Theme) => ({
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
  }
}));

const StyledRadio = styled(Radio)(({ theme }) => ({
  [`&.${radioClasses.root}`]: {
    width: 20,
    height: 20,
    padding: "0 !important",
    border: `1px solid ${theme.palette.DARK}`,
    marginRight: "10px !important"
  }
}));

export default function Expiraiton({
  disabled,
  linkEndTime,
  setExpirationTime
}: {
  disabled: boolean;
  linkEndTime: Date | null;
  setExpirationTime: (dateObject: Date) => void;
}) {
  const [isDatePickerOpen, setIsDatePickerOpen] = useState(false);
  const [dateValue, setDateValue] = useState<string>(
    linkEndTime !== null ? linkEndTime.getTime().toString() : "-1"
  );
  const classes = useStyles();

  const {
    isMobile,
    publicAccess: { isPublic }
  } = useContext(PermissionsDialogContext);

  const { formatMessage } = useIntl();

  const openDatePicker = () => {
    setIsDatePickerOpen(true);
  };

  const closeDatePicker = () => {
    setIsDatePickerOpen(false);
  };

  const expirationDateValues = {
    [NEVER_TIME]: formatMessage({ id: "never" }),
    [days30]: formatMessage(
      {
        id: "NDays"
      },
      { amount: 30 }
    )
  };

  const setExpirationTimeFunc = React.useCallback((newDate: Date) => {
    const is0 = newDate.getTime() === 0;
    let endOfDayDate = newDate;
    if (!is0) {
      endOfDayDate = DateTime.fromJSDate(newDate).endOf("day").toJSDate();
    }
    setExpirationTime(endOfDayDate);
    closeDatePicker();
    const time = endOfDayDate.getTime();
    setDateValue(
      Object.prototype.hasOwnProperty.call(expirationDateValues, time)
        ? time.toString()
        : "-1"
    );
  }, []);

  const handleChangeExpiration = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setExpirationTimeFunc(new Date(parseInt(e?.target?.value, 10)));
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

  let currentPickerValue = linkEndTime;
  if (linkEndTime === null || linkEndTime.getTime() === 0) {
    if (initialFocusedDate.getTime() > Date.now()) {
      currentPickerValue = initialFocusedDate;
    } else {
      currentPickerValue = maxDate || null;
    }
  }
  return (
    <Stack direction="row" justifyContent="space-between">
      <Stack
        direction="row"
        justifyContent="space-between"
        width="100%"
        alignItems={isMobile ? "flex-start" : "center"}
      >
        <Typography>
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
                    sx={{
                      color: theme => theme.palette.KYLO,
                      height: "17px",
                      width: "17px"
                    }}
                  />
                </IconButton>
              </Tooltip>
            )}
          </span>
        </Typography>
        <Stack
          direction={isMobile ? "column" : "row"}
          columnGap={2}
          rowGap={1.5}
        >
          <FormControl
            sx={{
              pl: "2px",
              display: "flex",
              flexDirection: "row !important",
              alignItems: "center"
            }}
            disabled={!isPublic}
          >
            <RadioGroup
              row
              name="expirationType"
              sx={{ columnGap: 2 }}
              onChange={handleChangeExpiration}
              value={dateValue}
            >
              <FormControlLabel
                sx={{ margin: 0 }}
                value={NEVER_TIME}
                control={<StyledRadio />}
                label={formatMessage({ id: "never" })}
              />
              <FormControlLabel
                sx={{ margin: 0 }}
                value={days30}
                control={<StyledRadio />}
                label={formatMessage({ id: "NDays" }, { amount: 30 })}
              />
            </RadioGroup>
          </FormControl>
          <Stack direction="row" alignItems="center">
            <DatePicker
              open={isDatePickerOpen}
              onClose={closeDatePicker}
              onChange={setExpirationTimeFunc}
              value={dateValue !== NEVER_TIME ? currentPickerValue : null}
              disablePast
              views={["year", "month", "day"]}
              onViewChange={() => {
                // keeps datepicker opened
                setIsDatePickerOpen(true);
              }}
              disabled={!isPublic}
              slotProps={{
                field: {
                  onClick: openDatePicker
                } as never
              }}
              slots={{
                field: CustomDatePickerField
              }}
            />
          </Stack>
        </Stack>
      </Stack>
    </Stack>
  );
}
