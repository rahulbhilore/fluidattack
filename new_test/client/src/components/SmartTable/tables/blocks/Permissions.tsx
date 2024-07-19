import React, { useCallback } from "react";
import { observer } from "mobx-react-lite";
import { Button, Typography, Tooltip } from "@mui/material";
import { FormattedMessage } from "react-intl";
import SVG from "react-inlinesvg";
import _ from "underscore";

import ResourceFile from "../../../../stores/resources/ResourceFile";
import permissionsSVG from "../../../../assets/images/permissions.svg";

type Props = {
  data: ResourceFile;
};

function Access({ data }: Props) {
  const { ownerType, uploadProgress } = data;

  const stop = useCallback(() => {
    data.stopUpload();
  }, [data.id]);

  if (uploadProgress)
    return (
      <Typography
        sx={{
          fontWeight: 900,
          marginLeft: "4px"
        }}
        variant="body2"
      >
        {`${uploadProgress}%`}
        <button type="button" onClick={stop}>
          X
        </button>
      </Typography>
    );

  const openPermissionsDialog = useCallback(() => {
    alert("Share api is not implemented yet");
    // ModalActions.shareBlock(id, libId, type, name);
  }, []);

  const isDisabled = ownerType === "PUBLIC";
  return isDisabled ? null : (
    <Tooltip
      id="permissionsForEntityTooltip"
      title={<FormattedMessage id="permissions" />}
      placement="top"
    >
      <Button
        onClick={openPermissionsDialog}
        onTouchEnd={openPermissionsDialog}
        data-component="manage_permissions"
        disabled={isDisabled}
        sx={{
          height: "36px",
          width: "36px",
          backgroundColor: "transparent",
          border: theme => `1px solid ${theme.palette.grey[500]}`,
          minWidth: "36px",
          p: 1,
          "&:hover svg > .st0": {
            fill: theme => theme.palette.LIGHT
          },
          "&:hover": {
            backgroundColor: theme => theme.palette.OBI
          },
          "& svg": {
            height: "20px"
          }
        }}
      >
        <SVG src={permissionsSVG}>
          <img src={permissionsSVG} alt="permissions" />
        </SVG>
      </Button>
    </Tooltip>
  );
}

export default observer(Access);
