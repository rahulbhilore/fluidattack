import { Tooltip, styled, useMediaQuery, useTheme } from "@mui/material";
import React, { useEffect, useRef, useState } from "react";
import { THUMBNAIL_WIDTH } from "./FileBlock";

const StyledFileLinkComponent = styled("a")(({ theme }) => ({
  fontSize: "12px",
  margin: "5px 0",
  color: theme.palette.DARK,
  textDecoration: "none",
  pointerEvents: "auto"
}));

type Props = {
  fileLink: string;
  calculatedCallback: () => void;
  dataComponent: string;
  name: string;
};

export default function FileLink({
  name,
  fileLink,
  calculatedCallback,
  dataComponent
}: Props) {
  const fileLinkElement = useRef<null | HTMLAnchorElement>(null);
  const [isTooltipToShow, setIsTooltipToShow] = useState(false);
  const [shortenedName, setShortenedName] = useState(name);
  const theme = useTheme();
  const isSmallerThanMd = useMediaQuery(theme.breakpoints.down("md"));

  const calculateWidth = () => {
    const currentWidth = fileLinkElement.current?.offsetWidth || 0;

    if (!currentWidth) return;

    if (currentWidth <= THUMBNAIL_WIDTH) {
      calculatedCallback();
      return;
    }

    const widthRatio = currentWidth / THUMBNAIL_WIDTH;
    const newNumberOfChars = Math.floor(name.length / widthRatio);

    const shouldShowTooltip = newNumberOfChars < name.length;

    let newNameToShow = name;
    if (shouldShowTooltip) {
      newNameToShow = `${name.substr(0, newNumberOfChars - 6)}...${name.substr(
        -3
      )}`;
    }
    calculatedCallback();
    setIsTooltipToShow(shouldShowTooltip);
    setShortenedName(newNameToShow);
  };

  useEffect(() => {
    calculateWidth();
  }, [name, isSmallerThanMd]);

  const styledComponent = (
    <StyledFileLinkComponent
      href={fileLink}
      data-component={dataComponent}
      data-name={name}
      ref={fileLinkElement}
    >
      {shortenedName}
    </StyledFileLinkComponent>
  );

  return isTooltipToShow ? (
    <Tooltip placement="bottom" title={name}>
      {styledComponent}
    </Tooltip>
  ) : (
    styledComponent
  );
}
