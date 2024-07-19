import { MenuItem, useTheme } from "@mui/material";
import React, { ReactNode, SyntheticEvent } from "react";

type PropType = {
  children: ReactNode;
  icon?: string;
  onClick: (e: SyntheticEvent) => void;
  isCurrent?: boolean;
  isLogout?: boolean;
  isBuy?: boolean;
  dataComponent?: string;
};

const CustomMenuItem = React.forwardRef<HTMLLIElement, PropType>(
  (props, ref) => {
    const {
      children,
      dataComponent = "",
      icon = "",
      isBuy = false,
      isCurrent = false,
      isLogout = false,
      onClick
    } = props;
    const theme = useTheme();

    return (
      <MenuItem
        ref={ref}
        onClick={onClick}
        sx={[
          {
            color: theme.palette.LIGHT,
            fontSize: 12,
            padding: "6px",
            minWidth: "120px",
            minHeight: "0",
            "&:hover": {
              backgroundColor: `${theme.palette.OBI} !important`
            },
            "&:focus": {
              backgroundColor: theme.palette.SNOKE
            },
            [theme.breakpoints.down("sm")]: {
              width: "100%"
            }
          },
          isCurrent && { backgroundColor: theme.palette.SNOKE },
          isLogout && { backgroundColor: theme.palette.JANGO },
          isBuy && {
            backgroundColor: theme.palette.YELLOW_BUTTON,
            height: "36px",
            lineHeight: "36px",
            display: "block",
            color: theme.palette.VADER,
            fontWeight: "bold",
            textAlign: "center",
            padding: 0,
            "&:hover,&:focus,&:active": {
              backgroundColor: `${theme.palette.YELLOW_BUTTON} !important`
            }
          }
        ]}
        data-component={dataComponent}
      >
        {Boolean(icon) && (
          <img
            src={icon}
            alt={children as string}
            style={{
              width: "20px",
              marginLeft: "5px",
              marginRight: "8px"
            }}
          />
        )}
        {children}
      </MenuItem>
    );
  }
);

export default CustomMenuItem;
