import React from "react";
import { makeStyles } from "@mui/styles";
import { Theme } from "@mui/material/styles";
import Grid from "@mui/material/Grid";
import BlocksOld from "../ResourcesPage/Blocks";
import Blocks from "./pages/Blocks";
import Fonts from "./pages/Fonts";
import FontsOld from "../ResourcesPage/Fonts";
import TemplatesOld from "../ResourcesPage/Templates";
import Templates from "./pages/Templates";
import applicationStore from "../../../stores/ApplicationStore";

export enum ResourceTypes {
  CUSTOM_TEMPLATES_TYPE,
  PUBLIC_TEMPLATES_TYPE,
  CUSTOM_FONTS_TYPE,
  COMPANY_FONTS_TYPE,
  BLOCKS_TYPE
}

const useStyles = makeStyles((theme: Theme) => ({
  root: {
    width: `calc(100% - ${theme.kudoStyles.SIDEBAR_WIDTH})`,
    marginLeft: theme.kudoStyles.SIDEBAR_WIDTH
  },
  rootMobile: {
    width: "100%"
  }
}));

type Props = {
  isMobile: boolean;
  activePageName: ResourceTypes | null;
  libId?: string;
  query?: string;
};

export default function ResourcesContent({
  isMobile,
  activePageName,
  libId = "",
  query = ""
}: Props) {
  const getActivePage = () => {
    const oldResourcesUsage = applicationStore.getOldResourcesUsage();

    switch (activePageName) {
      case ResourceTypes.CUSTOM_TEMPLATES_TYPE: {
        if (oldResourcesUsage.templates)
          return <TemplatesOld type="CUSTOM_TEMPLATES" />;
        return (
          <Templates
            type={ResourceTypes.CUSTOM_TEMPLATES_TYPE}
            libId={libId}
            query={query}
          />
        );
      }
      case ResourceTypes.PUBLIC_TEMPLATES_TYPE: {
        if (oldResourcesUsage.pTemplates)
          return <TemplatesOld type="PUBLIC_TEMPLATES" />;
        return (
          <Templates
            type={ResourceTypes.PUBLIC_TEMPLATES_TYPE}
            libId={libId}
            query={query}
          />
        );
      }
      case ResourceTypes.CUSTOM_FONTS_TYPE: {
        if (oldResourcesUsage.fonts) return <FontsOld />;
        return (
          <Fonts
            type={ResourceTypes.CUSTOM_FONTS_TYPE}
            libId={libId}
            query={query}
          />
        );
      }
      case ResourceTypes.COMPANY_FONTS_TYPE: {
        if (oldResourcesUsage.cFonts) return <FontsOld />;
        return (
          <Fonts
            type={ResourceTypes.COMPANY_FONTS_TYPE}
            libId={libId}
            query={query}
          />
        );
      }
      case ResourceTypes.BLOCKS_TYPE: {
        if (oldResourcesUsage.blocks)
          return <BlocksOld libId={libId} query={query} />;
        return <Blocks libId={libId} query={query} />;
      }
      default:
        return null;
    }
  };

  return (
    <Grid
      item
      sx={{
        width: theme =>
          isMobile ? "100%" : `calc(100% - ${theme.kudoStyles.SIDEBAR_WIDTH})`,
        marginLeft: theme =>
          isMobile ? "30px" : theme.kudoStyles.SIDEBAR_WIDTH
      }}
    >
      {getActivePage()}
    </Grid>
  );
}
