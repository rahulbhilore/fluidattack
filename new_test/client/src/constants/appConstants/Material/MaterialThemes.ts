import { ThemeOptions } from "@material-ui/core";
import ColorSchemes from "./ColorSchemes";

declare module "@material-ui/core" {
  interface Theme {
    kudoStyles: {
      FONT_STACK: string;
      HEADER_HEIGHT: string;
      MOBILE_SIDEBAR_WIDTH_MAX: string;
      MOBILE_SIDEBAR_WIDTH_MIN: string;
      MOBILE_SIDEBAR_WIDTH: string;
      SIDEBAR_WIDTH: string;
    };
  }

  interface ThemeOptions {
    kudoStyles: {
      FONT_STACK: string;
      HEADER_HEIGHT: string;
      MOBILE_SIDEBAR_WIDTH_MAX: string;
      MOBILE_SIDEBAR_WIDTH_MIN: string;
      MOBILE_SIDEBAR_WIDTH: string;
      SIDEBAR_WIDTH: string;
    };
  }
}

const baseTheme: ThemeOptions = {
  typography: {
    fontFamily: [
      "Open Sans",
      "Tahoma",
      "Geneva",
      "MS Gothic",
      "PingFang SC",
      "Heiti SC",
      "sans-serif"
    ].join(","),
    body1: {
      fontSize: "12px"
    }
  },
  kudoStyles: {
    FONT_STACK:
      "'Open Sans', Tahoma, Geneva, MS Gothic, PingFang SC, Heiti SC, sans-serif",
    HEADER_HEIGHT: "50px",
    SIDEBAR_WIDTH: "180px",
    MOBILE_SIDEBAR_WIDTH: "180px",
    MOBILE_SIDEBAR_WIDTH_MIN: "40px",
    MOBILE_SIDEBAR_WIDTH_MAX: "200px"
  },
  palette: {},
  // TODO: all overrides must be removed after we redefine all palette colors
  overrides: {
    MuiCssBaseline: {
      "@global": {
        "html,body,div#react": {
          height: "100%"
        },
        "div#react": {
          height: "100%",
          display: "flex"
        },
        "*": {
          outline: "none"
        },
        a: {
          color: "#3c5fcc",
          cursor: "pointer",
          textDecoration: "none"
        },
        body: {
          backgroundColor: "#F7F7F7",
          overflow: "hidden"
        },
        "*::-webkit-scrollbar": {
          backgroundColor: "#FFFFFF",
          width: "5px"
        },

        "*::-webkit-scrollbar-thumb": {
          backgroundColor: "#646464"
        },

        "*::-webkit-scrollbar-track": {
          backgroundColor: "#FFFFFF"
        }
      }
    },
    MuiPopover: {
      paper: {
        color: "#000000", // DARK
        backgroundColor: "#FFFFFF", // LIGHT
        "& .MuiMenuItem-root:hover": {
          backgroundColor: "#CFCFCF" // REY
        },
        "& .Mui-selected": {
          backgroundColor: "#D5D5D5" // slightly darker REY
        }
      }
    },
    MuiTooltip: {
      tooltip: {
        fontSize: ".75rem"
      }
    },
    MuiFormControlLabel: {
      label: {
        "&.Mui-disabled": {
          color: "#646464" // CLONE
        }
      }
    }
  }
};
baseTheme.palette = ColorSchemes.getActiveScheme();

export default baseTheme;
