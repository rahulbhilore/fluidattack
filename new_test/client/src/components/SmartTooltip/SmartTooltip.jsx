import React, { useState } from "react";
import propTypes from "prop-types";
import Tooltip from "@material-ui/core/Tooltip";

export default function SmartTooltip({
  children,
  title,
  forcedOpen,
  className,
  disableHoverListener,
  placement
}) {
  const [open, setOpen] = useState(forcedOpen);

  const handleTooltipClose = () => {
    setOpen(false);
  };

  const handleTooltipOpen = () => {
    setOpen(true);
  };
  return (
    <Tooltip
      onClose={handleTooltipClose}
      onOpen={handleTooltipOpen}
      disableHoverListener={disableHoverListener}
      open={forcedOpen && open}
      title={title}
      placement={placement}
      className={className}
    >
      {children}
    </Tooltip>
  );
}

SmartTooltip.propTypes = {
  children: propTypes.oneOfType([
    propTypes.arrayOf(propTypes.node),
    propTypes.node
  ]).isRequired,
  title: propTypes.oneOfType([propTypes.string, propTypes.node]).isRequired,
  forcedOpen: propTypes.bool,
  disableHoverListener: propTypes.bool,
  className: propTypes.string,
  placement: propTypes.string
};

SmartTooltip.defaultProps = {
  forcedOpen: false,
  disableHoverListener: false,
  className: "",
  placement: "top"
};
