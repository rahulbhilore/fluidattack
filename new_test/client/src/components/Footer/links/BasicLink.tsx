import React from "react";
import { FormattedMessage } from "react-intl";
import { Link, SxProps } from "@mui/material";
import UtmTracker from "../../../utils/UtmTracker";

type Props = {
  href: string;
  messageId: string;
  onClick?: (e: React.MouseEvent<HTMLAnchorElement, MouseEvent>) => void;
  dataComponent?: string;
  sx?: SxProps;
};

export default function BasicLink({
  href,
  messageId,
  onClick,
  dataComponent,
  sx = {}
}: Props) {
  return (
    <Link
      data-component={dataComponent}
      target="_blank"
      rel="noopener noreferrer"
      onClick={onClick}
      href={UtmTracker.updateLink(href)}
      aria-label={messageId}
      sx={{
        color: theme => theme.palette.REY,
        fontSize: theme => theme.typography.pxToRem(11),
        textDecoration: "underline",
        marginBottom: "5px",
        "&:hover,&:focus,&:active": {
          color: theme => theme.palette.REY
        },
        ...sx
      }}
    >
      <FormattedMessage id={messageId} />
    </Link>
  );
}
