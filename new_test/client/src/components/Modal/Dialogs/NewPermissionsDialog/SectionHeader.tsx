import { IconButton, Stack, Typography, styled } from "@mui/material";
import React, { useMemo } from "react";
import { ReactSVG } from "react-svg";
import ArrowDropDownSVG from "../../../../assets/images/dialogs/icons/arrowDropDown.svg";
import ArrowDropUpSVG from "../../../../assets/images/dialogs/icons/arrowDropUp.svg";

const StyledIconButton = styled(IconButton)(({ theme: { palette } }) => ({
  borderRadius: 0,
  color: palette.button.secondary.contained.textColor,
  backgroundColor: palette.button.secondary.contained.background.standard,
  width: 26,
  height: 26
}));

type ExpandButton = {
  isExpanded: boolean;
  onExpandButtonClick: () => void;
};

type PropType = {
  header: string;
  expandButton?: ExpandButton;
};

export default function SectionHeader({
  header,
  expandButton = {} as ExpandButton
}: PropType) {
  const { isExpanded, onExpandButtonClick } = expandButton;
  const showExpandButton = useMemo(
    () => Object.keys(expandButton).length > 0,
    [expandButton]
  );
  return (
    <Stack
      direction="row"
      alignItems="center"
      justifyContent="space-between"
      width="100%"
    >
      <Typography fontWeight={700}>{header}</Typography>
      {showExpandButton && (
        <StyledIconButton
          onClick={onExpandButtonClick}
          sx={{
            color: theme => theme.palette.textField.value.placeholder,
            "& .react-svg-icon > div": {
              display: "flex"
            }
          }}
          data-component="permissions-expand-button"
        >
          <ReactSVG
            src={isExpanded ? ArrowDropUpSVG : ArrowDropDownSVG}
            className="react-svg-icon"
          />
        </StyledIconButton>
      )}
    </Stack>
  );
}
