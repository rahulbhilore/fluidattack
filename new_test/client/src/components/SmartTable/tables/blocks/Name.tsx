import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { observer } from "mobx-react-lite";
import { Link } from "react-router";
import { styled } from "@mui/styles";
import Typography from "@mui/material/Typography";
import { Box, Tooltip } from "@mui/material";

import Thumbnail from "../../../Thumbnail";
import ResourceFile from "../../../../stores/resources/ResourceFile";
import ResourceFolder from "../../../../stores/resources/ResourceFolder";

import FileSVG from "../../../../assets/images/icons/file.svg";
import FolderSVG from "../../../../assets/images/icons/folder.svg";
// import FolderSharedSVG from "../../../../assets/images/icons/folderShared.svg";

const StyledImage = styled("img")(() => ({
  height: "32px",
  width: "36px"
}));

type Props = {
  name: string;
  data: ResourceFile | ResourceFolder;
};

function Name({ name, data }: Props) {
  const { type, id } = data;
  // const classes = useStyles();
  const [isTooltipToShow, setTooltip] = useState(false);
  const widthFixDiv = useRef<HTMLDivElement>();
  const nameSpan = useRef<HTMLElement>();

  // TODO: here should be a thumbnail where they will be implemended for bloks
  const thumbnail = null;

  let genericIcon = FileSVG;
  if (type === "folder") {
    genericIcon = FolderSVG;
  }

  const checkTooltip = () => {
    let newTooltip = false;
    if (
      (nameSpan.current?.offsetWidth || 0) >
      (widthFixDiv.current?.offsetWidth || 0)
    ) {
      newTooltip = true;
    }
    if (newTooltip !== isTooltipToShow) {
      setTooltip(newTooltip);
    }
  };

  useLayoutEffect(checkTooltip, []);

  useEffect(checkTooltip, [name]);

  const namePart = (isLink = false) => (
    <Typography
      sx={{
        marginLeft: theme => theme.spacing(2),
        color: theme => theme.palette.VADER,
        userSelect: "text",
        display: "inline-block",
        transitionDuration: "0.12s",
        verticalAlign: "middle",
        cursor: isLink ? "pointer" : "text",
        textDecoration: isLink ? "underline" : "none"
      }}
      // variant="body2"
      data-component="blockName"
      data-text={name}
      component="span"
      ref={nameSpan}
    >
      {name}
    </Typography>
  );
  let nameComponent = (
    <Box
      ref={widthFixDiv}
      sx={{
        "& a": {
          color: theme => theme.palette.JANGO,
          lineHeight: "80px"
        }
      }}
    >
      {type === "folder" && id ? (
        <Link to={`/resources/blocks/${id}`}>{namePart(true)}</Link>
      ) : (
        namePart(false)
      )}
    </Box>
  );

  if (isTooltipToShow)
    nameComponent = (
      <Tooltip placement="top" title={name}>
        {nameComponent}
      </Tooltip>
    );
  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "center",
        paddingLeft: theme => theme.spacing(2)
      }}
    >
      <Box
        sx={{
          height: "100%",
          width: "68px",
          display: "flex",
          position: "absolute",
          justifyContent: "center",
          alignItems: "center",
          "& img": {
            maxWidth: "100%",
            maxHeight: "100%"
          }
        }}
      >
        {type === "block" && thumbnail ? (
          <Thumbnail src={thumbnail} name={name} fileId={id} />
        ) : (
          <StyledImage src={genericIcon} alt={name} />
        )}
      </Box>
      <Box
        sx={{
          padding: "0 10px 0 88px",
          width: "100%",
          display: "inline-block"
        }}
      >
        {nameComponent}
      </Box>
    </Box>
  );
}

export default observer(Name);
