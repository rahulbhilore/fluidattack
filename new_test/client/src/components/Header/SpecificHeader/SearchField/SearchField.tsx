import { Grid, Theme, useTheme } from "@mui/material";
import { makeStyles } from "@mui/styles";
import React, { useEffect, useRef, useState } from "react";
import Isvg from "react-inlinesvg";
import { FormattedMessage } from "react-intl";
import { browserHistory } from "react-router";
import ApplicationActions from "../../../../actions/ApplicationActions";
import * as InputTypes from "../../../../constants/appConstants/InputTypes";
import * as InputValidationFunctions from "../../../../constants/validationSchemas/InputValidationFunctions";
import MainFunctions from "../../../../libraries/MainFunctions";
import ApplicationStore, {
  SEARCH_SWITCHED,
  USER_MENU_SWITCHED
} from "../../../../stores/ApplicationStore";
import KudoForm from "../../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../../Inputs/KudoInput/KudoInput";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";

import searchSVG from "../../../../assets/images/icons/search.svg";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";

const minimumCharsNumber = 2;

const useStyles = makeStyles((theme: Theme) => ({
  searchIconBlock: {
    cursor: "pointer",
    position: "absolute",
    right: "4px",
    top: "10px",
    backgroundColor: "transparent",
    border: "none",
    "& svg": {
      fill: `${theme.palette.REY} !important`,
      height: "18px"
    }
  },
  inputComponent: {
    float: "right",
    height: "36px",
    width: "100%",
    "& .MuiInputBase-input, & .MuiInputBase-input:hover, & .MuiInputBase-input:focus, & .MuiInputBase-input:active":
      {
        border: `1px solid ${theme.palette.VADER} !important`,
        color: `${theme.palette.LIGHT} !important`,
        backgroundColor: `${theme.palette.JANGO} !important`
      },
    "& .MuiInputBase-input::placeholder": {
      color: `${theme.palette.REY} !important`
    }
  }
}));

const SEARCH_TO_NORMAL_URLS = {
  "users/find": "users",
  "resources/blocks/find": "resources/blocks",
  "files/search": "files"
};

type PropType = {
  customPlaceholder?: string;
  isMobile: boolean;
  searchURL?: string;
};

export default function SearchField({
  customPlaceholder = "",
  searchURL = "",
  isMobile
}: PropType) {
  const theme = useTheme();
  let searchValue = "";
  if (MainFunctions.detectPageType().includes(searchURL)) {
    try {
      searchValue = decodeURIComponent(
        location.href.substring(location.href.lastIndexOf("/") + 1)
      );
    } catch (error) {
      SnackbarUtils.alertError(error.toString());
      searchValue = "";
    }
  }

  const [_value, _setValue] = useState(searchValue);
  const value = useRef(_value);
  const setValue = (newVal: string) => {
    _setValue(newVal);
    value.current = newVal;
  };
  const [showTooltip, setShowTooltip] = useState(
    searchValue.length > 0 && searchValue.length < minimumCharsNumber
  );

  const [isCollapsed, setCollapsed] = useState(false);
  const [isHided, setHided] = useState(false);

  useEffect(() => {
    const _unlisten = browserHistory.listen(location => {
      if (location.pathname.includes(searchURL)) {
        try {
          setValue(
            decodeURIComponent(
              location.pathname.substring(
                location.pathname.indexOf(`${searchURL}/`) +
                  searchURL.length +
                  1
              )
            )
          );
        } catch (Exception) {
          SnackbarUtils.alertError(Exception.toString());
          setValue("");
        }
      } else {
        setValue("");
      }
    });
    return _unlisten;
  }, [searchURL]);

  const userMenuSwitched = () => {
    if (!isMobile) return;

    if (ApplicationStore.getUserMenuState()) setHided(true);
    else setHided(false);
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(USER_MENU_SWITCHED, userMenuSwitched);
    return () => {
      ApplicationStore.removeChangeListener(
        USER_MENU_SWITCHED,
        userMenuSwitched
      );
    };
  }, [isCollapsed]);

  const searchSwitched = () => {
    setCollapsed(!isCollapsed);
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(SEARCH_SWITCHED, searchSwitched);
    return () => {
      ApplicationStore.removeChangeListener(SEARCH_SWITCHED, searchSwitched);
    };
  }, [isCollapsed]);

  useEffect(() => {
    setCollapsed(isMobile);
  }, [isMobile]);

  const searchRequest = ({ query }: { query: { value: string } }) => {
    if (query.value.length >= minimumCharsNumber) {
      const encodedValue = encodeURIComponent(value.current).replace(
        /[!'()*]/g,
        c => `%${c.charCodeAt(0).toString(16)}`
      );
      ApplicationActions.changePage(`/${searchURL}/${encodedValue}`);
    } else {
      if (location.href.includes(searchURL)) {
        const nonSearchURL = Object.prototype.hasOwnProperty.call(
          SEARCH_TO_NORMAL_URLS,
          searchURL
        )
          ? SEARCH_TO_NORMAL_URLS[
              searchURL as keyof typeof SEARCH_TO_NORMAL_URLS
            ]
          : "files";
        ApplicationActions.changePage(`/${nonSearchURL}`);
      }
      if (query.value.length !== 0) {
        SnackbarUtils.alertError({
          id: "minimumNCharsRequired",
          number: minimumCharsNumber
        });
      }
    }
  };

  const searchCollapseClick = () => {
    ApplicationActions.switchSearchCollapse();
  };

  useEffect(() => {
    setShowTooltip(
      value.current.length < minimumCharsNumber && value.current.length !== 0
    );
  }, [_value]);

  const classes = useStyles();

  if (isHided) return null;

  return (
    <Grid
      sx={[
        {
          alignSelf: "center",
          width: "400px",
          [theme.breakpoints.down("lg")]: {
            width: "200px"
          },
          [theme.breakpoints.down("sm")]: {
            width: "50px"
          }
        },
        !isCollapsed && {
          [theme.breakpoints.down("sm")]: {
            width: "100%"
          }
        }
      ]}
    >
      <KudoForm id="searchForm" onSubmitFunction={searchRequest} checkOnMount>
        <SmartTooltip
          placement="bottom"
          forcedOpen={showTooltip}
          disableHoverListener
          title={
            <FormattedMessage
              id="minimumNCharsRequired"
              values={{ number: minimumCharsNumber }}
            />
          }
        >
          <Grid sx={{ position: "relative", minHeight: "36px" }}>
            {!isCollapsed && (
              <KudoInput
                name="query"
                id="query"
                formId="searchForm"
                type={InputTypes.TEXT}
                classes={{
                  input: classes.inputComponent
                }}
                value={value.current}
                className="searchField"
                isEmptyValueValid
                validationFunction={InputValidationFunctions.any}
                placeHolder={customPlaceholder || "search"}
                onChange={setValue}
                inputDataComponent="search-input"
                isCheckOnExternalUpdate
              />
            )}
            <button
              aria-label="search"
              className={classes.searchIconBlock}
              style={{
                display: isMobile ? "inline-block" : "none"
              }}
              onClick={searchCollapseClick}
              type="button"
            >
              <Isvg cacheRequests={false} uniquifyIDs={false} src={searchSVG} />
            </button>
          </Grid>
        </SmartTooltip>
      </KudoForm>
    </Grid>
  );
}
