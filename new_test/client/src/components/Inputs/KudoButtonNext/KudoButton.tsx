import { Box, Button, CircularProgress } from "@mui/material";
import React, { forwardRef } from "react";
import { FormattedMessage } from "react-intl";
import { KudoButtonPropType } from "./types";

const KudoButton = forwardRef<HTMLButtonElement, KudoButtonPropType>(
  (
    {
      children,
      dataComponent = "",
      startIcon,
      loading = false,
      loadingLabelId = "loading",
      type,
      sx,
      ...rest
    },
    ref
  ) => (
    <Button
      {...rest}
      data-component={type === "submit" ? "submit-button" : dataComponent}
      variant="contained"
      disableElevation
      ref={ref}
      startIcon={
        loading ? (
          <Box sx={{ lineHeight: 0 }}>
            <CircularProgress color="inherit" size="1rem" thickness={5} />
          </Box>
        ) : (
          startIcon
        )
      }
      type={type}
      sx={{ textTransform: "initial", ...sx }}
    >
      {loading ? <FormattedMessage id={loadingLabelId} /> : children}
    </Button>
  )
);

export default KudoButton;
