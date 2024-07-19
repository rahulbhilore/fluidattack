import { Box, Grid, Typography, useTheme } from "@mui/material";
import React, { useEffect, useState } from "react";
import { useIntl } from "react-intl";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore, {
  SEARCH_SWITCHED
} from "../../../stores/ApplicationStore";
import FilesListStore from "../../../stores/FilesListStore";
import SearchField from "./SearchField/SearchField";

const getPageType = () => {
  const intl = useIntl();
  const pageType = MainFunctions.detectPageType();

  const getSearchURL = () => {
    if (pageType.includes("users")) return "users/find";
    if (pageType.includes("block")) return "resources/blocks/find";
    return "files/search";
  };

  if (pageType === "files/trash")
    return [getSearchURL(), intl.formatMessage({ id: "deletedFiles" })];

  if (pageType === "files/search")
    return [getSearchURL(), intl.formatMessage({ id: "search" })];

  if (pageType === "file")
    return [
      getSearchURL(),
      MainFunctions.shrinkString(FilesListStore.getCurrentFile().name)
    ];

  if (pageType === "company")
    return [getSearchURL(), intl.formatMessage({ id: "myCompany" })];

  if (pageType === "users/find")
    return [getSearchURL(), intl.formatMessage({ id: "foundUsers" })];

  if (pageType.includes("files")) {
    return [getSearchURL(), intl.formatMessage({ id: "myDrawings" })];
  }
  if (pageType.includes("resources")) {
    return [getSearchURL(), intl.formatMessage({ id: "resources" })];
  }

  return [
    getSearchURL(),
    intl.formatMessage({ id: MainFunctions.detectPageType() })
  ];
};

const isSearchAccessible = () => {
  const currentPage = MainFunctions.detectPageType();
  return (
    (currentPage.indexOf("files") > -1 ||
      currentPage.indexOf("users") > -1 ||
      currentPage.includes("blocks")) &&
    currentPage.indexOf("trash") === -1
  );
};

export default function OtherCasesHeader({ isMobile }: { isMobile: boolean }) {
  const [isPageNameVisible, setPageNameVisible] = useState(true);
  const [searchURL, pageTranslate] = getPageType();
  const search = isSearchAccessible();
  const theme = useTheme();

  const searchSwitched = () => {
    if (ApplicationStore.getSearchInputState()) {
      setPageNameVisible(false);
      return;
    }
    setPageNameVisible(true);
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(SEARCH_SWITCHED, searchSwitched);
    return () => {
      ApplicationStore.removeChangeListener(SEARCH_SWITCHED, searchSwitched);
    };
  }, [isPageNameVisible]);

  return (
    <Grid
      item
      sx={[
        {
          flexGrow: 1,
          height: "100%"
        },
        isMobile && { pl: 2 }
      ]}
    >
      <Grid
        container
        justifyContent={isPageNameVisible ? "space-between" : "flex-end"}
        sx={[
          {
            height: "100%",
            [theme.breakpoints.down("sm")]: {
              minHeight: "50px"
            }
          },
          search
            ? !isPageNameVisible && {
                width: "calc(100% + 16px)",
                marginLeft: "-16px"
              }
            : {
                [theme.breakpoints.down("sm")]: {
                  width: "100%"
                }
              }
        ]}
      >
        {isPageNameVisible && (
          <Box sx={{ display: "flex" }}>
            <Typography
              variant="h3"
              sx={{
                display: "inline-block",
                color: theme.palette.LIGHT,
                fontSize: ".8rem",
                alignSelf: "center",
                paddingLeft: "10px"
              }}
              data-component="page-title"
              fontSize={13}
            >
              {pageTranslate}
            </Typography>
          </Box>
        )}
        {search && (
          <SearchField
            searchURL={searchURL}
            customPlaceholder={
              searchURL === "files/search" ? "searchMyFiles" : "search"
            }
            isMobile={isMobile}
          />
        )}
      </Grid>
    </Grid>
  );
}
