import React, { useState } from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import Select from "@material-ui/core/Select";
import MenuItem from "@material-ui/core/MenuItem";
import InputBase from "@material-ui/core/InputBase";
import FormControl from "@material-ui/core/FormControl";
import { createStyles, makeStyles } from "@material-ui/core/styles";

const useStyles = makeStyles(theme =>
  createStyles({
    root: {
      width: props => props.width,
      [theme.breakpoints.down("xs")]: {
        width: props => props.mobileWidht,
        paddingLeft: "5px",
        paddingRight: "5px"
      }
    },
    select: {
      color: theme.palette.CLONE,
      textAlign: "left",
      fontSize: "12px",
      padding: "11px 0px 11px 12px",
      [theme.breakpoints.down("xs")]: {
        padding: "6px 0px 6px 12px"
      }
    },
    icon: {
      color: theme.palette.CLONE
    },
    input: {
      borderRadius: 4,
      position: "relative",
      backgroundColor: theme.palette.LIGHT,
      border: `1px solid #ced4da`,
      fontSize: 12,
      transition: theme.transitions.create(["border-color", "box-shadow"]),
      "&:focus": {
        borderRadius: 4,
        borderColor: "#80bdff",
        boxShadow: "0 0 0 0.2rem rgba(0,123,255,.25)"
      }
    }
  })
);

export default function ToolbarSelect(props) {
  const classes = useStyles(props);
  const {
    onChange,
    value,
    options,
    forceClose,
    clearForceClose,
    onSelectOpen,
    onSelectClose
  } = props;
  const [isOpen, setOpenState] = useState(false);

  if (forceClose) {
    setTimeout(() => {
      setOpenState(false);
      clearForceClose();
    }, 0);
  }

  return (
    <FormControl
      className={clsx(classes.root, isOpen ? "tableFilesFilterOpened" : null)}
    >
      <Select
        variant="standard"
        classes={{
          select: classes.select,
          icon: classes.icon
        }}
        value={value}
        onChange={onChange}
        onOpen={() => {
          setOpenState(true);
          if (onSelectOpen) onSelectOpen();
        }}
        onClose={() => {
          setOpenState(false);
          if (onSelectClose) onSelectClose();
        }}
        open={isOpen}
        input={<InputBase className={classes.input} />}
        MenuProps={{
          anchorOrigin: {
            vertical: "bottom",
            horizontal: "left"
          },
          transformOrigin: {
            vertical: "top",
            horizontal: "left"
          },
          getContentAnchorEl: null
        }}
      >
        {options.map(option => (
          <MenuItem value={option.value} key={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </Select>
    </FormControl>
  );
}

ToolbarSelect.propTypes = {
  onChange: PropTypes.func.isRequired,
  value: PropTypes.string.isRequired,
  forceClose: PropTypes.bool.isRequired,
  clearForceClose: PropTypes.func.isRequired,
  onSelectOpen: PropTypes.func.isRequired,
  onSelectClose: PropTypes.func.isRequired,
  options: PropTypes.arrayOf(
    PropTypes.shape({
      value: PropTypes.string.isRequired,
      label: PropTypes.oneOfType([PropTypes.string, PropTypes.node]).isRequired
    })
  ).isRequired
};
