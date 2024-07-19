import React from "react";
import { styled, Button } from "@mui/material";
import ObjectIcons from "../../../constants/appConstants/ObjectIcons";
import UserInfoStore from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";

const StyledButton = styled(Button)(({ theme }) => ({
  border: `solid 1px ${theme.palette.REY}`,
  backgroundColor: theme.palette.LIGHT,
  display: "block",
  pointerEvents: "none",
  margin: "0 auto",
  borderRadius: 0,
  padding: 0
}));

const StyledImg = styled("img")(() => ({
  border: "none",
  background: "none"
}));

type Props = {
  name: string;
  onClick: () => void;
  dataComponent: string;
  width: number;
  height: number;
};

export default function OtherFileButton(props: Props) {
  const { name, onClick, width, height, dataComponent } = props;
  const svgType: string = UserInfoStore.getIconClassName(
    MainFunctions.getExtensionFromName(name),
    "file",
    name
  );

  // just in case, but we should
  // always be able to get from the dictionary.
  let svgLink = `images/icons/${svgType}.svg`;
  if (Object.prototype.hasOwnProperty.call(ObjectIcons, `${svgType}SVG`)) {
    svgLink = ObjectIcons[`${svgType}SVG`];
  }

  return (
    <StyledButton
      style={{ width, height }}
      type="button"
      onClick={e => {
        e.preventDefault();
        onClick();
      }}
    >
      <StyledImg
        style={{ width, height, maxWidth: width }}
        src={svgLink}
        alt={name}
        data-component={dataComponent}
        data-name={name}
      />
    </StyledButton>
  );
}
