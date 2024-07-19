import { FormControlLabel, Switch, styled, switchClasses } from "@mui/material";
import switchBaseClasses from "@mui/material/internal/switchBaseClasses";
import React, { useMemo } from "react";
import { useIntl } from "react-intl";
import { KudoSwitchProps } from "./types";

const Constants = {
  WIDTH: 58,
  HEIGHT: 31,
  THUMB_HEIGHT: 21,
  THUMB_WIDTH: 21,
  PADDING_X: 8
};

const StyledSwitch = styled((props: KudoSwitchProps) => (
  <Switch focusVisibleClassName=".Mui-focusVisible" disableRipple {...props} />
))(props => {
  const { theme, specialDimensions } = props;

  const height = useMemo(
    () => (specialDimensions ? specialDimensions.height : Constants.HEIGHT),
    [specialDimensions]
  );
  const width = useMemo(
    () => (specialDimensions ? specialDimensions.width : Constants.WIDTH),
    [specialDimensions]
  );
  const thumbHeight = useMemo(
    () =>
      specialDimensions
        ? specialDimensions.thumbHeight
        : Constants.THUMB_HEIGHT,
    [specialDimensions]
  );
  const thumbWidth = useMemo(
    () =>
      specialDimensions ? specialDimensions.thumbWidth : Constants.THUMB_WIDTH,
    [specialDimensions]
  );
  const paddingX = useMemo(
    () =>
      specialDimensions ? specialDimensions.paddingX : Constants.PADDING_X,
    [specialDimensions]
  );
  return {
    width,
    height,
    padding: 0,
    margin: theme.spacing(1),
    [`& .${switchBaseClasses.root}`]: {
      padding: 0,
      top: (height - thumbHeight) / 2,
      left: paddingX,
      transitionDuration: "150ms",
      [`&.${switchClasses.checked}`]: {
        transform: `translateX(${width - thumbWidth - 2 * paddingX}px)`,
        [`& + .${switchClasses.track}`]: {
          borderRadius: 50,
          backgroundColor: theme.palette.switch.active.bg,
          opacity: 1,
          border: 0
        },
        [`&.${switchClasses.disabled} + .${switchClasses.track}`]: {
          backgroundColor: theme.palette.disabled.bg
        }
      },
      [`&.${switchClasses.disabled} .${switchClasses.thumb}`]: {
        color: theme.palette.disabled.textColor
      },
      [`&.${switchClasses.disabled} + .${switchClasses.track}`]: {
        opacity: theme.palette.mode === "light" ? 0.7 : 0.3,
        backgroundColor: theme.palette.disabled.bg
      }
    },
    [`& .${switchClasses.thumb}`]: {
      color: theme.palette.switch.thumbBgColor,
      boxSizing: "border-box",
      width: thumbWidth,
      height: thumbHeight
    },
    [`& .${switchClasses.track}`]: {
      borderRadius: 90,
      backgroundColor: theme.palette.switch.unactive.bgColor,
      opacity: 1,
      width,
      transition: theme.transitions.create(["background-color"], {
        duration: 150
      })
    }
  };
});

export default function KudoSwitch({
  dataComponent = "",
  fullWidth = false,
  label = "",
  labelPlacement,
  required,
  switchSx,
  sx,
  translateLabel = false,
  customLabelComponent,
  ...others
}: KudoSwitchProps) {
  const { formatMessage } = useIntl();
  let labelToRender: React.ReactNode = label;
  if (customLabelComponent) {
    labelToRender = customLabelComponent;
  } else if (translateLabel) {
    labelToRender = formatMessage({ id: label });
  }
  return (
    <FormControlLabel
      componentsProps={{ typography: { variant: "body2" } }}
      label={labelToRender}
      labelPlacement={labelPlacement}
      required={required}
      sx={{
        m: 0,
        ...sx,
        ...(fullWidth
          ? { display: "flex", justifyContent: "space-between" }
          : {})
      }}
      control={
        <StyledSwitch
          data-component={dataComponent}
          sx={switchSx}
          {...others}
        />
      }
    />
  );
}
