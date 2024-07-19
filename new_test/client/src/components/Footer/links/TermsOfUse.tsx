import React from "react";
import { SxProps } from "@mui/material";
import ApplicationActions from "../../../actions/ApplicationActions";
import BasicLink from "./BasicLink";

export default function TermsOfUse({
  link,
  sx
}: {
  link: string;
  sx?: SxProps;
}) {
  const toggleTermsVisibility = (
    e: React.MouseEvent<HTMLAnchorElement, MouseEvent>
  ) => {
    if (!link.includes("//")) {
      e.preventDefault();
      ApplicationActions.toggleTermsVisibility();
    }
  };
  return (
    <BasicLink
      dataComponent="termsOfUseLink"
      onClick={toggleTermsVisibility}
      href={link}
      messageId="terms"
      sx={sx}
    />
  );
}
