import React, { forwardRef } from "react";
import { styled } from "@material-ui/core/styles";
import { SnackbarContent } from "notistack";

const StyledDiv = styled("div")(({ theme }) => ({
  marginTop: "10px",
  backgroundColor: theme.palette.DARK,
  padding: "10px 10px 10px 10px",
  fontFamily: theme.kudoStyles.FONT_STACK,
  color: theme.palette.LIGHT
}));

const StyledBackDrop = styled("div")(({ theme }) => ({
  position: "fixed",
  width: "200vw",
  height: "200vh",
  backgroundColor: theme.palette.DARK,
  opacity: 0.7,
  left: "-100vw",
  top: "-50vh",
  zIndex: -1
}));

const StyledText = styled("p")(() => ({
  margin: 0,
  lineHeight: "18px",
  fontSize: "14px"
}));

const Snackbar = forwardRef((props, ref) => (
  <SnackbarContent ref={ref}>
    <StyledDiv data-component="testMessage">
      <StyledBackDrop />
      <StyledText>
        Performance tests in progress. Don`t touch anything, it may affect on
        result of tests.
      </StyledText>
    </StyledDiv>
  </SnackbarContent>
));

export default Snackbar;
