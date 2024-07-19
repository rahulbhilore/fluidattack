import { Badge, Box, Button, Tooltip, badgeClasses } from "@mui/material";
import React, { ReactNode, SyntheticEvent } from "react";

type PropType = {
  badge?: number;
  caption: ReactNode;
  dataComponent: string;
  icon: string | ReactNode;
  iconWidth?: number;
  iconHeight?: number;
  isDisabled?: boolean;
  onClick: (e?: SyntheticEvent) => void;
  onKeyDown?: (e?: React.KeyboardEvent<unknown>) => void;
};

export default function IconButton(props: PropType) {
  const {
    badge,
    caption,
    dataComponent = "",
    icon,
    iconWidth = 20,
    iconHeight = 20,
    isDisabled = false,
    onClick,
    onKeyDown = () => null
  } = props;

  let content: ReactNode =
    typeof icon === "string" ? (
      <img
        style={{
          cursor: "pointer",
          width: iconWidth,
          height: iconHeight
        }}
        src={icon}
        alt={caption as string}
      />
    ) : (
      icon
    );

  // wrap with badge if needed
  if (badge !== undefined) {
    content = (
      <Badge
        sx={{
          [`&.${badgeClasses.root}`]: {
            margin: "0!important"
          },
          [`& .${badgeClasses.badge}`]: {
            color: theme => theme.palette.LIGHT,
            background: theme => theme.palette.OBI,
            fontSize: "10px",
            minWidth: "18px",
            height: "18px"
          }
        }}
        badgeContent={badge}
        invisible={!badge}
        color="primary"
        max={99}
      >
        {content}
      </Badge>
    );
  }

  return (
    <Box
      sx={{
        width: "20px",
        height: "20px",
        display: "flex",
        alignItems: "center",
        justifyContent: "center"
      }}
    >
      <Tooltip placement="bottom" title={caption}>
        <Button
          sx={{
            background: "none",
            border: "none",
            outline: "none",
            padding: 0,
            margin: 0,
            minWidth: "unset",
            "&:hover": {
              backgroundColor: "unset"
            }
          }}
          onClick={onClick}
          onKeyDown={onKeyDown}
          type="button"
          data-component={dataComponent}
          disabled={isDisabled}
        >
          {content}
        </Button>
      </Tooltip>
    </Box>
  );
}
