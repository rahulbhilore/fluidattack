import React, { useState, useEffect } from "react";
import { FormattedMessage } from "react-intl";
import { Typography, SxProps } from "@mui/material";
import ApplicationStore, {
  CONFIG_LOADED,
  UPDATE
} from "../../stores/ApplicationStore";

type Props = {
  messageId?: string;
  isSidebar?: boolean;
  sx?: SxProps;
};
export default function Revision({ messageId, isSidebar, sx }: Props) {
  const [revision, setRevision] = useState(
    ApplicationStore.getApplicationSetting("revision")
  );
  useEffect(() => {
    const changeRevision = () => {
      setRevision(ApplicationStore.getApplicationSetting("revision"));
    };
    ApplicationStore.addChangeListener(CONFIG_LOADED, changeRevision);
    ApplicationStore.addChangeListener(UPDATE, changeRevision);
    return () => {
      ApplicationStore.removeChangeListener(CONFIG_LOADED, changeRevision);
      ApplicationStore.removeChangeListener(UPDATE, changeRevision);
    };
  });
  return (
    <Typography
      variant="body2"
      sx={{
        cursor: "text",
        userSelect: "text",
        fontSize: theme => theme.typography.pxToRem(11),
        marginRight: "30px",
        textAlign: "center",
        margin: "5px 0",
        padding: "0 10px",
        width: "100%",
        lineHeight: "15px",
        marginTop: "5px",
        color: theme => theme.palette.CLONE,
        whiteSpace: "nowrap",
        ...(isSidebar
          ? {
              borderRight: theme => `10px solid ${theme.palette.VADER}`,
              overflow: "hidden"
            }
          : {}),
        ...sx
      }}
      data-component="revision"
    >
      <FormattedMessage
        id={messageId || "versionShort"}
        values={{ version: revision, Version: revision }}
      />
    </Typography>
  );
}
