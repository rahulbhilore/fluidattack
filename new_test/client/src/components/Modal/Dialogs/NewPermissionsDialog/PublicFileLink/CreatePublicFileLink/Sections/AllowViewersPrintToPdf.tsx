import { Stack, Typography } from "@mui/material";
import React, { useContext } from "react";
import { FormattedMessage } from "react-intl";
import KudoSwitch from "../../../../../../Inputs/KudoSwitchNext/KudoSwitch";
import { SpecialDimensions } from "../../../../../../Inputs/KudoSwitchNext/types";
import { PermissionsDialogContext } from "../../../PermissionsDialogContext";

export default function AllowViewersPrintToPdf({
  switchDimensions,
  handleChangeExportSwitch
}: {
  handleChangeExportSwitch: (_: unknown, checked: boolean) => void;
  switchDimensions: SpecialDimensions;
}) {
  const {
    publicAccess: { isPublic, isExport }
  } = useContext(PermissionsDialogContext);

  return (
    <Stack direction="row" alignItems="center" justifyContent="space-between">
      <Typography>
        <FormattedMessage id="exportToPDF" />
      </Typography>
      <KudoSwitch
        specialDimensions={switchDimensions}
        disabled={!isPublic}
        defaultChecked={isExport}
        onChange={handleChangeExportSwitch}
      />
    </Stack>
  );
}
