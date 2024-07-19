import _ from "underscore";
import Storage from "../../../utils/Storage";

export default class ColorSchemes {
  static defaultScheme = {
    type: "dark",
    primary: {
      main: "#1E2023", // snoke
      light: "#45474b",
      dark: "#000000",
      contrastText: "#ffffff"
    },
    secondary: {
      main: "#333538", // jango
      light: "#5c5f62",
      dark: "#0c0f12",
      contrastText: "#ffffff"
    },
    BLACK: "#2B2523",
    BLUE: "#254CA9",
    BORDER_COLOR: "#979797",
    BREADCRUMB_BOTTOM_BORDER: "#ECECEC",
    CLONE: "#646464",
    DARK: "#000000",
    GREY_BACKGROUND: "#F7F7F7",
    GREY_TEXT: "#E2E2E2",
    JANGO: "#333538",
    KYLO: "#B82115",
    LIGHT: "#FFFFFF",
    OBI: "#124daf",
    RENAME_FIELD_BACKGROUND: "#5B5B5B",
    REY: "#CFCFCF",
    SNOKE: "#1E2023",
    VADER: "#141518",
    VENERO: "#31343b",
    WHITE_HEADER_NAME: "#E5E5E5",
    YELLOW_BREADCRUMB: "#FFFDDE",
    YELLOW_BUTTON: "#E7D300",
    YODA: "#39A935"
  };

  static getActiveScheme() {
    let customScheme = Storage.store("customScheme");
    if (customScheme) {
      try {
        customScheme = JSON.parse(customScheme);
        return _.extend(_.clone(this.defaultScheme), customScheme);
      } catch (ex) {
        return this.defaultScheme;
      }
    }
    return this.defaultScheme;
  }

  static saveNewScheme(scheme) {
    Storage.store("customScheme", JSON.stringify(scheme));
  }

  static getDefaultScheme() {
    return this.defaultScheme;
  }
}
