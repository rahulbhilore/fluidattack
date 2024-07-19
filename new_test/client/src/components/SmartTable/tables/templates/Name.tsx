import React, { useState, useRef, useEffect, useLayoutEffect } from "react";
import { Link } from "react-router";
import { observer } from "mobx-react-lite";
import { Box, Typography, styled } from "@mui/material";

import TableEditField from "../../TableEditField";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import folderSVG from "../../../../assets/images/icons/folder.svg";
import drawingSVG from "../../../../assets/images/icons/drawing.svg";
import UploadProgress from "../files/innerComponents/UploadProgress";
import FilesListStore, {
  ENTITY_RENAME_MODE
} from "../../../../stores/FilesListStore";
import ResourceFile from "../../../../stores/resources/ResourceFile";
import { OBJECT_TYPES } from "../../../../stores/resources/BaseResourcesStore";

const StyledImage = styled("img")(({ theme }) => ({
  height: "32px",
  width: "36px",
  display: "inline-block",
  verticalAlign: "middle",
  marginRight: theme.spacing(1)
}));

type Props = {
  data: ResourceFile;
};

function Name({ data }: Props) {
  const { id: _id, name, type, uploadProgress, storage } = data;
  const [id, setId] = useState(_id);
  const [isTooltipToShow, setTooltip] = useState(false);
  const [isRenameMode, setRenameMode] = useState(false);
  const widthFixDiv = useRef<HTMLDivElement>(null);
  const nameSpan = useRef<HTMLDivElement>(null);

  const checkTooltip = () => {
    let newTooltip = false;
    if (
      (nameSpan.current?.offsetWidth || 0) >
      (widthFixDiv?.current?.offsetWidth || 0)
    ) {
      newTooltip = true;
    }
    if (newTooltip !== isTooltipToShow) {
      setTooltip(newTooltip);
    }
  };

  const renameListener = (mode: boolean) => {
    setRenameMode(mode as boolean);
  };

  useEffect(() => {
    setId(_id);
  }, [_id]);

  useLayoutEffect(checkTooltip, []);

  useEffect(checkTooltip, [name]);

  useEffect(() => {
    FilesListStore.addEventListener(
      `${ENTITY_RENAME_MODE}${id}`,
      renameListener
    );
    return () => {
      FilesListStore.removeEventListener(
        `${ENTITY_RENAME_MODE}${id}`,
        renameListener
      );
    };
  }, [id]);

  const handleCancel = () => {
    data.stopUpload();
  };

  let namePart = (
    <Typography
      component="span"
      ref={nameSpan}
      sx={{
        marginTop: "2px",
        display: "inline-block",
        fontSize: theme => theme.typography.pxToRem(12)
      }}
    >
      {name}
    </Typography>
  );

  if (type === OBJECT_TYPES.FOLDER) {
    let prefix = "my";

    if (storage?.getStorageName() === "PublicTemplatesStore") prefix = "public";

    namePart = (
      <Link to={`/resources/templates/${prefix}/${id}`}>
        <Typography
          component="span"
          ref={nameSpan}
          sx={{
            color: theme => `${theme.palette.DARK} !important`,
            fontSize: theme => theme.typography.pxToRem(12)
          }}
        >
          {name}
        </Typography>
      </Link>
    );
  }

  return (
    <Box
      sx={{
        paddingLeft: theme => theme.spacing(3),
        display: "flex",
        "& .shortcuts_wrapper": {
          width: "50%"
        }
      }}
    >
      {type === "file" ? (
        <StyledImage src={drawingSVG} alt={name} />
      ) : (
        <StyledImage src={folderSVG} alt={name} />
      )}

      {isRenameMode ? (
        <TableEditField
          fieldName="name"
          value={name}
          id={id || ""}
          type="template"
          extensionEdit
        />
      ) : (
        <SmartTooltip
          forcedOpen={isTooltipToShow}
          placement="top"
          title={name || ""}
        >
          <Box
            ref={widthFixDiv}
            data-component="objectName"
            data-text={name}
            sx={{
              marginTop: "6px"
            }}
          >
            {namePart}
          </Box>
        </SmartTooltip>
      )}
      {!!uploadProgress && (
        <Box
          sx={{
            width: "45vw",
            right: "10px",
            position: "absolute",
            marginTop: "7px"
          }}
        >
          <UploadProgress
            name="upload"
            id={id || ""}
            customClass="templates"
            showLabel
            value={uploadProgress}
            cancelFunction={handleCancel}
          />
        </Box>
      )}
    </Box>
  );
}

export default observer(Name);
