import {
  ButtonProps,
  SimplePaletteColorOptions,
  createTheme
} from "@mui/material";

declare module "@mui/material" {
  interface Theme {
    kudoStyles: {
      FONT_STACK: string;
      HEADER_HEIGHT: string;
      MOBILE_SIDEBAR_WIDTH_MAX: string;
      MOBILE_SIDEBAR_WIDTH_MIN: string;
      MOBILE_SIDEBAR_WIDTH: string;
      SIDEBAR_WIDTH: string;
      THRESHOLD_TO_SHOW_LOGIN_MENU: number;
      THRESHOLD_TO_SHOW_OPEN_IN_APP_MOBILE_MENU: number;
      THRESHOLD_TO_SHOW_DRAW_MENU: number;
      THRESHOLD_TO_DRAWING_PAGE: number;
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
      THRESHOLD_TO_SHOW_LOGIN_MENU: number;
      THRESHOLD_TO_SHOW_OPEN_IN_APP_MOBILE_MENU: number;
      THRESHOLD_TO_SHOW_DRAW_MENU: number;
      THRESHOLD_TO_DRAWING_PAGE: number;
    };
  }

  interface Palette {
    disabled: {
      bg: string;
      textColor: string;
    };
    bg: string;
    button: {
      textColor: string;
      primary: {
        contained: {
          background: { standard: string };
          textColor: string;
        };
      };
      secondary: {
        contained: {
          background: { standard: string };
          textColor: string;
        };
      };
    };
    checkboxes: {
      row: {
        active: string;
        standard: string;
        text: {
          active: string;
          unactive: string;
        };
      };
    };
    drawer: {
      bg: string;
      item: {
        background: { active: string; standard: string };
        textColor: string;
      };
    };
    fg: string;
    font: { textField: { labelDefault: string } };
    header: string;
    installationBanner: {
      bgColor: string;
      stepDetailColor: string;
      buttonBg: string;
    };
    radiobutton: {
      active: { standard: string };
      unactive: { standard: string };
      checkedIconBgColor: string;
    };
    switch: {
      active: { bg: string };
      unactive: {
        bgColor: string;
      };
      thumbBgColor: string;
    };
    table: {
      account: {
        detail: { standard: string };
      };
    };
    textField: {
      outlined: { value: { placeholder: string } };
      value: { placeholder: string; filled: string };
    };
    greyCool: {
      "04": string;
      34: string;
    };
    drawingMenu: {
      bg: string;
    };
    quickAccess: {
      activeIcon: string;
    };
    BLACK: string;
    BLUE: string;
    BORDER_COLOR: string;
    CLONE: string;
    JANGO: string;
    LIGHT: string;
    OBI: string;
    SNOKE: string;
    YELLOW_BUTTON: string;
    DARK: string;
    REY: string;
    ONYX: string;
    FLASH_WHITE: string;
    CELTIC_BLUE: string;
    VADER: string;
    KYLO: string;
    WHITE_HEADER_NAME: string;
    RENAME_FIELD_BACKGROUND: string;
  }

  interface PaletteOptions {
    disabled?: {
      bg: string;
      textColor: string;
    };
    bg: string;
    button: {
      textColor: string;
      primary: {
        contained: {
          background: { standard: string };
          textColor: string;
        };
      };
      secondary: {
        contained: {
          background: { standard: string };
          textColor: string;
        };
      };
    };
    checkboxes: {
      row: {
        active: string;
        standard: string;
        text: {
          active: string;
          unactive: string;
        };
      };
    };
    drawer: {
      bg: string;
      item: {
        background: { active: string; standard: string };
        textColor: string;
      };
    };
    fg: string;
    font: { textField: { labelDefault: string } };
    header: string;
    installationBanner: {
      bgColor: string;
      stepDetailColor: string;
      buttonBg: string;
    };
    radiobutton: {
      active: { standard: string };
      unactive: { standard: string };
      checkedIconBgColor: string;
    };
    switch: {
      active: { bg: string };
      unactive?: {
        bgColor: string;
      };
      thumbBgColor: string;
    };
    table: {
      account: {
        detail: { standard: string };
      };
    };
    textField: {
      outlined: { value: { placeholder: string } };
      value: { placeholder: string; filled: string };
    };
    greyCool: {
      "04": string;
      34: string;
    };
    drawingMenu: {
      bg: string;
    };
    quickAccess: {
      activeIcon: string;
    };
    BLACK: string;
    BLUE: string;
    BORDER_COLOR: string;
    CLONE: string;
    JANGO: string;
    LIGHT: string;
    OBI: string;
    SNOKE: string;
    YELLOW_BUTTON: string;
    DARK: string;
    REY: string;
    ONYX: string;
    FLASH_WHITE: string;
    CELTIC_BLUE: string;
    VADER: string;
    KYLO: string;
    WHITE_HEADER_NAME: string;
    RENAME_FIELD_BACKGROUND: string;
  }
}

let baseTheme = createTheme({
  breakpoints: {
    values: {
      xs: 0,
      sm: 600,
      md: 960,
      lg: 1280,
      xl: 1920
    }
  },
  typography: {
    fontFamily: '"Roboto","Helvetica","Arial",sans-serif'
  },
  components: {
    MuiMenuItem: {
      styleOverrides: {
        root: {
          fontSize: 14
        }
      }
    },
    MuiSelect: {
      styleOverrides: {
        select: {
          fontSize: 14
        }
      }
    },
    MuiAutocomplete: {
      styleOverrides: {
        option: {
          fontSize: 12
        }
      }
    }
  },
  kudoStyles: {
    FONT_STACK:
      "'Open Sans', Tahoma, Geneva, MS Gothic, PingFang SC, Heiti SC, sans-serif",
    HEADER_HEIGHT: "50px",
    MOBILE_SIDEBAR_WIDTH_MAX: "200px",
    MOBILE_SIDEBAR_WIDTH_MIN: "44px",
    MOBILE_SIDEBAR_WIDTH: "180px",
    SIDEBAR_WIDTH: "180px",
    THRESHOLD_TO_SHOW_LOGIN_MENU: 1000,
    THRESHOLD_TO_SHOW_OPEN_IN_APP_MOBILE_MENU: 600,
    THRESHOLD_TO_SHOW_DRAW_MENU: 1000,
    THRESHOLD_TO_DRAWING_PAGE: 1155
  },
  palette: {
    error: { main: "#C2172A", light: "#F24848", dark: "#A20000" },
    info: { main: "#3626A7", light: "#6F63C0", dark: "#2E218F" },
    primary: { main: "#254CA9", light: "#4B6CB8", dark: "#191919" },
    secondary: { main: "#333538", light: "#5c5f62", dark: "#0c0f12" },
    success: { main: "#55A630", light: "#85BF6A", dark: "#3A7121" },
    warning: { main: "#7209B7", light: "#903DC6", dark: "#4E067D" },

    bg: "#FFFFFF",
    button: {
      textColor: "#FFFFFF",
      primary: {
        contained: {
          background: { standard: "#254CA9" },
          textColor: "#FFFFFF"
        }
      },
      secondary: {
        contained: {
          background: { standard: "#E5E7EC" },
          textColor: "#010204"
        }
      }
    },
    checkboxes: {
      row: {
        active: "#EDF0F8",
        standard: "#FFFFFF",
        text: {
          active: "#010204",
          unactive: "#010204"
        }
      }
    },
    divider: "#D5D7E1",
    drawer: {
      bg: "#333538",
      item: {
        background: { active: "#254CA9", standard: "#1e1f23" },
        textColor: "#FFFFFF"
      }
    },
    fg: "#010204",
    font: { textField: { labelDefault: "#010204" } },
    header: "#254CA9",
    installationBanner: {
      bgColor: "#272727",
      stepDetailColor: "#A3A3A3",
      buttonBg: "#1247A2"
    },
    radiobutton: {
      active: { standard: "#254CA9" },
      unactive: { standard: "#DEE4F2" },
      checkedIconBgColor: "#FFFFFF"
    },
    switch: {
      active: { bg: "#254CA9" },
      thumbBgColor: "#FFFFFF"
    },
    table: { account: { detail: { standard: "#F6F6F8" } } },
    textField: {
      outlined: { value: { placeholder: "#7D839A" } },
      value: { placeholder: "#191919CC", filled: "#191919" }
    },
    greyCool: {
      "04": "#F6F6F8",
      "34": "#7D839A"
    },
    drawingMenu: {
      bg: "#4A4A4A"
    },
    quickAccess: {
      activeIcon: "#EF7C28"
    },
    BLACK: "#191919",
    BLUE: "#254CA9",
    BORDER_COLOR: "#D4D4D4",
    CLONE: "#646464",
    JANGO: "#333538",
    LIGHT: "#FFFFFF",
    OBI: "#124daf",
    SNOKE: "#1E2023",
    YELLOW_BUTTON: "#E7D300",
    DARK: "#000000",
    REY: "#CFCFCF",
    ONYX: "#333538",
    FLASH_WHITE: "#F1F1F1",
    CELTIC_BLUE: "#2461C7",
    VADER: "#141518",
    KYLO: "#B82115",
    WHITE_HEADER_NAME: "#E5E5E5",
    RENAME_FIELD_BACKGROUND: "#5B5B5B"
  }
});

baseTheme = createTheme(baseTheme, {
  components: {
    MuiSelect: {
      styleOverrides: {
        select: {
          fontSize: 14,
          color: baseTheme.palette.textField.outlined.value.placeholder,
          padding: baseTheme.spacing(1.25, 2)
        }
      }
    },
    MuiButton: {
      styleOverrides: {
        root: {
          fontSize: "14px",
          fontWeight: 400,
          borderRadius: baseTheme.spacing(0.5),
          color: baseTheme.palette.button.textColor,
          "&.Mui-disabled": {
            backgroundColor: baseTheme.palette.secondary.main
          }
        },
        text: {
          "&.Mui-disabled": {
            backgroundColor: "transparent"
          }
        },
        sizeLarge: {
          fontSize: baseTheme.spacing(2),
          lineHeight: baseTheme.spacing(3.5),
          padding: baseTheme.spacing(2, 2)
        },
        sizeMedium: {
          lineHeight: baseTheme.spacing(3),
          padding: baseTheme.spacing(1.5, 2)
        },
        sizeSmall: {
          lineHeight: baseTheme.spacing(2.5),
          padding: baseTheme.spacing(1, 2)
        }
      },
      defaultProps: {
        disableRipple: true
      }
    }
  }
});

// eslint-disable-next-line import/no-mutable-exports
let lightTheme = createTheme(baseTheme, {
  palette: {
    mode: "light",
    secondary: { main: "#EDEEF2", light: "#F6F6F8", dark: "#D5D7E1" },
    disabled: { bg: "#EDEEF2", textColor: "#ACB1C3" },
    bgColor: { dark: "#878DA6", light: "#DDDFE7" },
    switch: {
      unactive: {
        bgColor: "#DDDFE7"
      }
    }
  },
  components: {
    MuiButton: {
      styleOverrides: {
        containedSecondary: {
          color: baseTheme.palette.button.secondary.contained.textColor
        },
        text: {
          color: baseTheme.palette.button.secondary.contained.textColor
        },
        outlined: {
          color: baseTheme.palette.button.secondary.contained.textColor
        }
      }
    },
    MuiRadio: {
      defaultProps: {
        disableRipple: true
      },
      styleOverrides: {
        root: {
          padding: `${baseTheme.spacing(1)} !important`
        }
      }
    },
    MuiSwitch: {
      styleOverrides: {
        root: {
          margin: "0 !important"
        }
      }
    },
    MuiFormControlLabel: {
      styleOverrides: {
        label: {
          fontSize: 14,
          lineHeight: 2,
          color: baseTheme.palette.fg,
          fontWeight: 400
        }
      }
    }
  }
});

lightTheme = createTheme(lightTheme, {
  components: {
    MuiButton: {
      styleOverrides: {
        root: ({ ownerState }: { ownerState: ButtonProps }) => ({
          "&.Mui-disabled": {
            backgroundColor: lightTheme.palette.secondary.main,
            color: lightTheme.palette.disabled.textColor
          },
          "&:hover": {
            background: (
              lightTheme.palette[
                ownerState.color as never
              ] as SimplePaletteColorOptions
            ).light
          }
        })
      }
    }
  }
});

// eslint-disable-next-line import/no-mutable-exports
let darkTheme = createTheme(baseTheme, {
  palette: {
    mode: "dark",
    secondary: { main: "#2E3039", light: "#515463", dark: "#D5D7E1" },
    disabled: { bg: "#2E3039", textColor: "#6E7388" },
    switch: {
      unactive: {
        bgColor: "#878DA6"
      }
    }
  }
});

darkTheme = createTheme(darkTheme, {
  components: {
    MuiButton: {
      styleOverrides: {
        root: ({ ownerState }: { ownerState: ButtonProps }) => ({
          "&.Mui-disabled": {
            color: darkTheme.palette.disabled.textColor,
            backgroundColor: darkTheme.palette.secondary.main
          },
          "&:hover": {
            background: (
              darkTheme.palette[
                ownerState.color as never
              ] as SimplePaletteColorOptions
            ).light
          }
        })
      }
    }
  }
});

export { darkTheme, lightTheme };
