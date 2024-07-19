import { PaletteMode, ThemeProvider } from "@mui/material";
import React, { ReactNode, createContext, useMemo, useState } from "react";
import { darkTheme, lightTheme } from "./theme";

export const ColorModeContext = createContext<{ toggleColorMode: () => void }>({
  toggleColorMode: () => {}
});
type PropType = {
  children: ReactNode;
};
export default function KudoThemeProvider({ children }: PropType) {
  const [mode, setMode] = useState<PaletteMode>("light");

  const contextValue = useMemo(
    () => ({
      toggleColorMode: () =>
        setMode(prev => (prev === "dark" ? "light" : "dark"))
    }),
    []
  );

  const theme = useMemo(
    () => (mode === "dark" ? darkTheme : lightTheme),
    [mode]
  );

  return (
    <ColorModeContext.Provider value={contextValue}>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </ColorModeContext.Provider>
  );
}
