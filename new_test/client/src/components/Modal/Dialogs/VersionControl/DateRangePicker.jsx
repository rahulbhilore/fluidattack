import React, { useState, useContext } from "react";
import PropTypes from "prop-types";
import { DatePicker, MuiPickersContext } from "@material-ui/pickers";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";

const useStyles = makeStyles(theme => ({
  day: {
    width: 36,
    height: 36,
    fontSize: theme.typography.caption.fontSize,
    margin: "1px 2px",
    color: theme.palette.DARK,
    fontWeight: theme.typography.fontWeightMedium,
    padding: 0,
    transition: "none"
  },
  hidden: {
    opacity: 0,
    pointerEvents: "none"
  },
  current: {
    fontWeight: 600
  },
  daySelected: {
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT,
    "&:hover": {
      backgroundColor: theme.palette.OBI
    }
  },
  dayDisabled: {
    pointerEvents: "none",
    color: theme.palette.CLONE
  },
  isSelected: {
    borderRadius: 0,
    borderTop: `2px dashed ${theme.palette.OBI}`,
    borderBottom: `2px dashed ${theme.palette.OBI}`,
    "&:hover": {
      backgroundColor: theme.palette.WHITE
    }
  },
  beginCap: {
    borderBottomLeftRadius: "50%",
    borderTopLeftRadius: "50%",
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT,
    "&:hover": {
      backgroundColor: theme.palette.OBI
    }
  },
  endCap: {
    borderBottomRightRadius: "50%",
    borderTopRightRadius: "50%",
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT,
    "&:hover": {
      backgroundColor: theme.palette.OBI
    }
  },
  hoverCap: {
    "&:hover": {
      backgroundColor: theme.palette.OBI,
      color: theme.palette.LIGHT
    }
  }
}));

export default function DateRangePicker({
  value,
  onChange,
  onClose,
  ...props
}) {
  const [begin, setBegin] = useState(value[0]);
  const [end, setEnd] = useState(value[1]);
  const [hover, setHover] = useState(undefined);
  const utils = useContext(MuiPickersContext);
  const classes = useStyles();

  const min = Math.min(begin, end || hover);
  const max = Math.max(begin, end || hover);

  const renderDay = (day, selectedDate, dayInCurrentMonth, dayComponent) =>
    React.cloneElement(dayComponent, {
      onClick: e => {
        e.stopPropagation();
        if (!begin) setBegin(day);
        else if (!end) {
          setEnd(day);
          onChange(
            [begin, day].sort(
              (date1, date2) => date1.getTime() - date2.getTime()
            )
          );
          onClose();
        } else {
          setBegin(day);
          setEnd(undefined);
        }
      },
      onMouseEnter: () => setHover(day),
      className: clsx(classes.day, {
        [classes.hidden]: dayComponent.props.hidden,
        [classes.current]: dayComponent.props.current,
        [classes.dayDisabled]: dayComponent.props.disabled,
        [classes.isSelected]: day >= min && day <= max && min !== max,
        [classes.beginCap]: utils.isSameDay(day, min) && min !== max,
        [classes.endCap]: utils.isSameDay(day, max) && min !== max,
        [classes.daySelected]: utils.isSameDay(day, min) && min === max,
        [classes.hoverCap]: begin && end
      })
    });

  return (
    <DatePicker
      // eslint-disable-next-line react/jsx-props-no-spreading
      {...props}
      value={begin}
      renderDay={renderDay}
      onClose={() => {
        if (onClose) onClose();
      }}
      onChange={() => {}}
    />
  );
}

DateRangePicker.propTypes = {
  value: PropTypes.arrayOf(PropTypes.number).isRequired,
  onClose: PropTypes.func.isRequired,
  onChange: PropTypes.func.isRequired
};
