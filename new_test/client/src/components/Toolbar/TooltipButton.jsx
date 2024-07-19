import React from "react";
import PropTypes from "prop-types";
import Isvg from "react-inlinesvg";
import Tooltip from "@material-ui/core/Tooltip";
import { createStyles, makeStyles } from "@material-ui/core/styles";

const useStyles = makeStyles(theme =>
  createStyles({
    root: {
      width: "32px",
      height: "32px",
      border: "none",
      boxShadow: "none",
      borderRadius: 0,
      padding: 0,
      cursor: "pointer",
      margin: "0 20px 0 0",
      backgroundColor: "transparent",
      "@media (min-width: 600px) and (max-width: 767px)": {
        margin: "0 10px 0 0"
      },
      "&:hover .st0": {
        fill: theme.palette.OBI
      },
      "&[disabled] .st0": {
        fill: theme.palette.REY
      }
    }
  })
);

export default function TooltipButton(props) {
  const classes = useStyles();
  const { onClick, tooltipTitle, icon, disabled, id, dataComponent } = props;

  let btn = (
    <button
      id={id}
      type="button"
      className={classes.root}
      onClick={onClick}
      disabled={disabled}
      data-component={dataComponent}
      aria-label={tooltipTitle}
    >
      <Isvg cacheRequests={false} uniquifyIDs={false} src={icon} />
    </button>
  );

  if (!disabled) {
    btn = (
      <Tooltip placement="bottom" title={tooltipTitle}>
        {btn}
      </Tooltip>
    );
  }

  return btn;
}

TooltipButton.propTypes = {
  onClick: PropTypes.func.isRequired,
  tooltipTitle: PropTypes.oneOfType([PropTypes.string, PropTypes.node])
    .isRequired,
  disabled: PropTypes.bool.isRequired,
  icon: PropTypes.string.isRequired,
  id: PropTypes.string.isRequired,
  dataComponent: PropTypes.string.isRequired
};
