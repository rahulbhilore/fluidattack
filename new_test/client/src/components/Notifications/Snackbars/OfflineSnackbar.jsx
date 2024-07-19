import React, { forwardRef, useCallback, useEffect } from "react";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import PropTypes from "prop-types";

import { styled } from "@material-ui/core/styles";

const StyledDiv = styled("div")(({ theme }) => ({
  display: "flex",
  flexFlow: "column",
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
  opacity: 0.5,
  left: "-100vw",
  top: "-50vh",
  zIndex: -1
}));

const StyledText = styled("p")(() => ({
  margin: 0,
  lineHeight: "18px",
  fontSize: "14px"
}));

const StyledButton = styled("button")(({ theme }) => ({
  padding: "8px 20px",
  textDecoration: "none",
  display: "block",
  backgroundColor: theme.palette.OBI,
  color: theme.palette.LIGHT,
  textTransform: "uppercase",
  fontWeight: 600,
  borderWidth: "1px",
  borderColor: theme.palette.OBI,
  marginTop: "10px",
  textAlign: "center",
  "&:hover, &:focus, &:visited": {
    backgroundColor: theme.palette.OBI,
    borderColor: theme.palette.LIGHT,
    color: theme.palette.LIGHT,
    textTransform: "uppercase",
    cursor: "pointer"
  }
}));

const Snackbar = forwardRef((props, ref) => {
  const { closeSnackbar } = useSnackbar();

  const close = useCallback(() => {
    closeSnackbar(props.id);
  }, [closeSnackbar]);

  useEffect(() => {
    window.addEventListener("online", close);
    return () => {
      window.removeEventListener("online", close);
    };
  }, []);

  const handleReload = () => {
    location.reload();
  };

  return (
    <SnackbarContent ref={ref}>
      <StyledDiv>
        <StyledBackDrop />
        <StyledText>
          <FormattedMessage id="offlineMessage" />
        </StyledText>
        <StyledButton onClick={handleReload} type="button">
          <FormattedMessage id="reload" />
        </StyledButton>
      </StyledDiv>
    </SnackbarContent>
  );
});

export default Snackbar;

Snackbar.propTypes = {
  id: PropTypes.number.isRequired
};
