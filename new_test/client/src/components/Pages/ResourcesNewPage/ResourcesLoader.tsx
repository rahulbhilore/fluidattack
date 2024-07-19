import React, { useState, useMemo, useEffect } from "react";
import { useIntl } from "react-intl";
import Grid from "@mui/material/Grid";

import MainFunctions from "../../../libraries/MainFunctions";
import ResourcesSideBar from "./ResourcesSideBar";
import ResourcesContent, { ResourceTypes } from "./ResourcesContent";

type Props = {
  params: {
    libId: string;
    query: string;
  };
};

export default function ResourcesLoader({ params }: Props) {
  const { formatMessage } = useIntl();
  const isMobileMode = () => MainFunctions.isMobileDevice();

  const [isMobile] = useState(isMobileMode());

  const getActivePageName = useMemo(() => {
    const { pathname } = location;

    if (pathname.includes("templates") && pathname.includes("public"))
      return ResourceTypes.PUBLIC_TEMPLATES_TYPE;
    if (pathname.includes("templates") && pathname.includes("my"))
      return ResourceTypes.CUSTOM_TEMPLATES_TYPE;
    if (pathname.includes("fonts") && pathname.includes("public"))
      return ResourceTypes.COMPANY_FONTS_TYPE;
    if (pathname.includes("fonts") && pathname.includes("my"))
      return ResourceTypes.CUSTOM_FONTS_TYPE;
    if (pathname.includes("blocks")) return ResourceTypes.BLOCKS_TYPE;

    return null;
  }, [location.pathname]);

  useEffect(() => {
    document.title = `${
      window.ARESKudoConfigObject.defaultTitle
    } | ${formatMessage({
      id: "resources"
    })}`;
  }, [formatMessage]);

  return (
    <Grid
      container
      sx={{
        width: "100vw",
        paddingTop: "50px",
        flexWrap: "nowrap"
      }}
    >
      <ResourcesSideBar isMobile={isMobile} />
      <ResourcesContent
        isMobile={isMobile}
        activePageName={getActivePageName}
        // eslint-disable-next-line react/jsx-props-no-spreading
        {...params}
      />
    </Grid>
  );
}
