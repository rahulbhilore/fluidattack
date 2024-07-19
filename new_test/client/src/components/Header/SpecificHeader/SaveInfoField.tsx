import { Tooltip, Typography } from "@mui/material";
import React, { useMemo } from "react";
import { FormattedMessage, useIntl } from "react-intl";

type PropType = {
  changer: string;
  timeStamp: number;
};

function SaveInfoField({ timeStamp, changer }: PropType) {
  const { formatTime, formatDate } = useIntl();
  const dateStr = formatDate(timeStamp, {
    day: "2-digit",
    month: "short",
    year: "numeric"
  });
  const timeStr = formatTime(timeStamp);
  const timestampDisp = useMemo(() => {
    if (timeStamp) {
      const lastSavedDate = new Date(timeStamp);
      const currentDate = new Date();
      const diffHrs =
        (currentDate.getMilliseconds() - lastSavedDate.getMilliseconds()) /
        1000 /
        3600;
      // if more than 24h or not the same day then display full date)
      return diffHrs > 24 || lastSavedDate.getDate() !== currentDate.getDate()
        ? dateStr
        : timeStr;
    }
    return null;
  }, [timeStamp]);

  if (!timeStamp) return null;
  return (
    <Tooltip
      placement="bottom"
      title={
        <FormattedMessage
          id="drawingSavedToolTip"
          values={{ time: timeStr, date: dateStr, user: changer }}
        />
      }
    >
      <Typography
        sx={{
          textAlign: "left",
          fontSize: 12,
          userSelect: "text",
          display: "initial",
          color: theme => theme.palette.LIGHT
        }}
        data-component="last_save_details"
        variant="body2"
      >
        <FormattedMessage
          id="drawingSavedAt"
          values={{ time: timestampDisp }}
        />
      </Typography>
    </Tooltip>
  );
}

export default SaveInfoField;
