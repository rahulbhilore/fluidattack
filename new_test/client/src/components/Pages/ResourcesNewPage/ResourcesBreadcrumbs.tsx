import React, { useEffect, useState } from "react";
import { observer } from "mobx-react-lite";
import { Link } from "react-router";
import { Typography, styled } from "@mui/material";
import Box from "@mui/material/Box";
import ResourceFolder, {
  ROOT_FOLDER
} from "../../../stores/resources/ResourceFolder";
import { ResourcePathInterface } from "../../../stores/resources/BaseInterfases";

import homeSVG from "../../../assets/images/home.svg";

const StyledImage = styled("img")(() => ({
  width: "16px"
}));

type Props = {
  currentFolder: ResourceFolder | undefined;
  staticPatch: string;
};

function ResourcesBreadcrumbs({ currentFolder, staticPatch }: Props) {
  const [path, setPath] = useState<ResourcePathInterface[]>([]);

  useEffect(() => {
    if (!currentFolder) return;

    const infoPath = currentFolder.getBreadcrumbsInfo();
    setPath(infoPath);
  }, [currentFolder?.fetchedPatch]);

  if (!currentFolder) return null;

  const buildItem = (item: ResourcePathInterface, index: number) => {
    const isLast = index === path.length - 1;

    let innerComponent = null;

    if (item._id === ROOT_FOLDER) {
      innerComponent = <StyledImage src={homeSVG} />;
    } else {
      innerComponent = (
        <Typography sx={{ color: "black" }} key={index}>
          {item.name}
        </Typography>
      );
    }

    if (isLast) return innerComponent;

    return (
      <>
        <Link to={`${staticPatch}/${item._id ? item._id : ""}`}>
          {innerComponent}
        </Link>
        <Typography sx={{ color: "black", px: 1 }}>/</Typography>
      </>
    );
  };

  return (
    <Box
      sx={{
        display: "flex",
        ml: 4
      }}
    >
      {path.map((item, index) => buildItem(item, index))}
    </Box>
  );
}

export default observer(ResourcesBreadcrumbs);
