import React, { Component } from "react";
import Immutable, { List } from "immutable";
import styled from "@material-ui/core/styles/styled";
import Box from "@material-ui/core/Box";
import _ from "underscore";
import { SortDirection } from "react-virtualized";

import SmartTable from "../../SmartTable/SmartTable";
import FontsActions from "../../../actions/FontsActions";
import FontsStore from "../../../stores/FontsStore";
import FontsToolbar from "./FontsToolbar";
import UserInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import {
  GET_COMPANY_FONTS_SUCCESS,
  GET_CUSTOM_FONTS_SUCCESS,
  REMOVE,
  REMOVE_SUCCESS,
  UPLOAD_COMPANY_FONT_FAIL,
  UPLOAD_COMPANY_FONT_SUCCESS,
  UPLOAD_CUSTOM_FONT,
  UPLOAD_CUSTOM_FONT_FAIL,
  UPLOAD_CUSTOM_FONT_SUCCESS
} from "../../../constants/FontsConstants";
import Loader from "../../Loader";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import fontNameTable from "../../SmartTable/tables/oldFonts/FontName";
import fontFamilyTable from "../../SmartTable/tables/oldFonts/FontFamily";
import fontSizeTable from "../../SmartTable/tables/oldFonts/FontSize";
import { sortByName } from "../../../utils/FileSort";

const StyledBox = styled(Box)(() => ({
  height: "84vh"
}));

const StyledTableBox = styled(Box)(({ theme }) => ({
  "& .ReactVirtualized__Table__headerColumn": {
    fontSize: theme.typography.pxToRem(12)
  },
  "& .ReactVirtualized__Table__headerColumn:first-of-type": {
    marginLeft: "38px"
  }
}));

const columns = new List([
  { dataKey: "name", label: "fontName", width: 0.65 },
  { dataKey: "fontFamily", label: "fontFamily", width: 0.25 },
  { dataKey: "size", label: "size", width: 0.1 }
]);

const presentation = new Immutable.Map({
  name: fontNameTable,
  fontFamily: fontFamilyTable,
  size: fontSizeTable
});

let sortDirection = SortDirection.ASC;

export const sortBySize = (a, b, direction) => {
  const aSize = a.size || 0;
  const bSize = b.size || 0;
  if (aSize !== bSize) {
    return aSize > bSize ? 1 : -1;
  }
  return sortByName(a, b, direction);
};

const customSorts = {
  name: (a, b) => sortByName(a.toJS(), b.toJS(), sortDirection),
  fontFamily: (a, b) => sortByName(a.toJS(), b.toJS(), sortDirection),
  size: (a, b) => sortBySize(a.toJS(), b.toJS(), sortDirection)
};

export const CUSTOM_FONTS = "custom";
export const COMPANY_FONTS = "company";

export default class Fonts extends Component {
  constructor(props) {
    super(props);
    this.state = {
      type: null,
      fonts: null,
      isLoaded: false,
      isSortRequired: false
    };
  }

  componentDidMount() {
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUser);
    FontsStore.addChangeListener(GET_CUSTOM_FONTS_SUCCESS, this.onFontLoad);
    FontsStore.addChangeListener(GET_COMPANY_FONTS_SUCCESS, this.onFontLoad);
    FontsStore.addChangeListener(REMOVE, this.onFontsUpdate);
    FontsStore.addChangeListener(REMOVE_SUCCESS, this.loadFonts);
    FontsStore.addChangeListener(UPLOAD_CUSTOM_FONT, this.onFontsUpdate);
    FontsStore.addChangeListener(
      UPLOAD_COMPANY_FONT_SUCCESS,
      this.onFontsUpdate
    );
    FontsStore.addChangeListener(UPLOAD_CUSTOM_FONT_SUCCESS, this.loadFonts);
    FontsStore.addChangeListener(UPLOAD_COMPANY_FONT_SUCCESS, this.loadFonts);

    FontsStore.addChangeListener(UPLOAD_CUSTOM_FONT_FAIL, this.loadFontsFail);
    FontsStore.addChangeListener(UPLOAD_COMPANY_FONT_FAIL, this.loadFontsFail);

    if (UserInfoStore.getUserInfo("id")) this.loadFonts();
  }

  componentDidUpdate() {
    const { type } = this.state;
    const newType = location.pathname.includes("public")
      ? COMPANY_FONTS
      : CUSTOM_FONTS;
    if (newType === type) return;

    this.loadFonts();
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
    FontsStore.removeChangeListener(GET_CUSTOM_FONTS_SUCCESS, this.onFontLoad);
    FontsStore.removeChangeListener(GET_COMPANY_FONTS_SUCCESS, this.onFontLoad);
    FontsStore.removeChangeListener(REMOVE, this.onFontsUpdate);
    FontsStore.removeChangeListener(REMOVE_SUCCESS, this.loadFonts);
    FontsStore.removeChangeListener(UPLOAD_CUSTOM_FONT, this.onFontsUpdate);
    FontsStore.removeChangeListener(UPLOAD_CUSTOM_FONT_SUCCESS, this.loadFonts);
    FontsStore.removeChangeListener(
      UPLOAD_COMPANY_FONT_SUCCESS,
      this.onFontsUpdate
    );
    FontsStore.removeChangeListener(
      UPLOAD_COMPANY_FONT_SUCCESS,
      this.loadFonts
    );

    FontsStore.removeChangeListener(
      UPLOAD_CUSTOM_FONT_FAIL,
      this.loadFontsFail
    );
    FontsStore.removeChangeListener(
      UPLOAD_COMPANY_FONT_FAIL,
      this.loadFontsFail
    );
  }

  onUser = () => {
    this.loadFonts();
  };

  onFontLoad = () => {
    const { type } = this.state;
    const fonts =
      type === CUSTOM_FONTS
        ? FontsStore.getCustomFonts()
        : FontsStore.getCompanyFonts();

    this.setState({
      fonts: Immutable.fromJS(
        fonts
          .filter(font => !_.isEmpty(font))
          .map(font => ({
            _id: font.id,
            name: font.file,
            size: font.size,
            fontFamily: font?.faces[0]?.fontFamily,
            url: font.url
          }))
      ),
      isLoaded: true
    });
  };

  onFontsUpdate = () => {
    this.setState({
      isLoaded: false
    });
  };

  loadFonts = () => {
    const { type: stateType } = this.state;

    const type = location.pathname.includes("public")
      ? COMPANY_FONTS
      : CUSTOM_FONTS;

    if (type === CUSTOM_FONTS) {
      FontsActions.getCustomFonts().catch(() => {
        SnackbarUtils.alertError({ id: "error" });
      });
    } else {
      const companyId = UserInfoStore.getUserInfo("company")?.id;
      FontsActions.getCompanyFonts(companyId).catch(() => {
        SnackbarUtils.alertError({ id: "error" });
      });
    }

    this.setState({
      isLoaded: false
    });

    if (type !== stateType) {
      this.setState({ type });
    }
  };

  loadFontsFail = () => {
    this.setState({
      isLoaded: true
    });

    SnackbarUtils.alertError({ id: "fontUploadFail" });
  };

  beforeSort = direction => {
    sortDirection = direction;
    const { isSortRequired } = this.state;
    if (isSortRequired) this.setState({ isSortRequired: false });
  };

  render() {
    const { type, fonts, isLoaded } = this.state;

    if (!isLoaded)
      return (
        <StyledBox>
          <FontsToolbar type={type} />
          <Loader />
        </StyledBox>
      );

    return (
      <StyledTableBox>
        <FontsToolbar type={type} />
        <SmartTable
          customSorts={customSorts}
          data={fonts}
          columns={columns}
          presentation={presentation}
          beforeSort={this.beforeSort}
          tableType="oldFonts"
          rowHeight={80}
        />
      </StyledTableBox>
    );
  }
}
