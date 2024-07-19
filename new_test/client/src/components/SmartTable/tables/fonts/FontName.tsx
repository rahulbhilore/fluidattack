import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { observer } from "mobx-react-lite";
import { Link } from "react-router";
import { Box, Typography, styled } from "@mui/material";

import FileSVG from "../../../../assets/images/icons/file.svg";
import folderSVG from "../../../../assets/images/icons/folder.svg";
import FontResourceFile from "../../../../stores/resources/fonts/FontResourceFile";
import { OBJECT_TYPES } from "../../../../stores/resources/BaseResourcesStore";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";

const FontIcon = styled("img")(({ theme }) => ({
  marginLeft: theme.spacing(3),
  width: "40px"
}));

type Props = {
  data: FontResourceFile;
};

function FontName({ data }: Props) {
  const { id: _id, name, type, storage } = data;
  const [id, setId] = useState(_id);
  const [isTooltipToShow, setTooltip] = useState(false);
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

  useEffect(() => {
    setId(_id);
  }, [_id]);

  useLayoutEffect(checkTooltip, []);

  useEffect(checkTooltip, [name]);

  let namePart = (
    <Typography component="span" ref={nameSpan}>
      {name}
    </Typography>
  );

  if (type === OBJECT_TYPES.FOLDER) {
    let prefix = "my";

    if (storage?.getStorageName() === "CompanyFontsStore") {
      prefix = "public";
    }

    namePart = (
      <Link to={`/resources/fonts/${prefix}/${id}`}>
        <Typography
          component="span"
          ref={nameSpan}
          sx={{
            color: theme => `${theme.palette.DARK} !important`
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
        display: "flex",
        gap: 3
      }}
    >
      <Box>
        <FontIcon src={type === "file" ? FileSVG : folderSVG} alt={name} />
      </Box>
      <Box
        sx={{
          display: "flex",
          alignItems: "center"
        }}
      >
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
      </Box>
    </Box>
  );
}

export default observer(FontName);
