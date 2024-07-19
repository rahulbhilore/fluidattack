import React, { forwardRef, useCallback, useState } from "react";
import { styled } from "@material-ui/core/styles";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import PropTypes from "prop-types";
import Collapse from "@mui/material/Collapse";
import QuickTour from "../../../Header/QuickTour/QuickTour";
import Storage from "../../../../utils/Storage";

const StyledDiv = styled("div")(({ theme }) => ({
  padding: "10px 10px 20px 10px",
  fontFamily: theme.kudoStyles.FONT_STACK,
  backgroundColor: theme.palette.VADER,
  color: theme.palette.LIGHT,
  width: "320px"
}));

const StyledH4 = styled("h4")(() => ({
  fontSize: "14px",
  textAlign: "left",
  margin: "0 0 4px 0",
  padding: 0
}));

const StyledP = styled("p")(() => ({
  lineHeight: "16px"
}));

const StyledSpan = styled("span")(({ theme }) => ({
  color: theme.palette.LIGHT,
  textDecoration: "underline",
  display: "inline-block",
  paddingTop: "2px",
  cursor: "pointer"
}));

const StyledButton = styled("button")(({ theme }) => ({
  marginTop: "-4px",
  backgroundColor: theme.palette.OBI,
  float: "right",
  borderRadius: 0,
  color: theme.palette.LIGHT,
  borderColor: theme.palette.OBI,
  borderWidth: "1px",
  fontWeight: 600,
  textTransform: "uppercase",
  padding: "6px 18px",
  textDecoration: "none",
  cursor: "pointer",
  "&:hover, &:focus, &:visited": {
    backgroundColor: theme.palette.OBI,
    borderColor: theme.palette.LIGHT,
    color: theme.palette.LIGHT,
    textTransform: "uppercase",
    cursor: "pointer"
  }
}));

const Snackbar = forwardRef((props, ref) => {
  const { id, revision } = props;
  const release = revision.split(".", 2).join(".");

  const { closeSnackbar } = useSnackbar();
  const closeSnack = useCallback(() => {
    closeSnackbar(id);
  }, [closeSnackbar]);

  const skipIt = e => {
    e.preventDefault();
    Storage.store("lastViewedVersion", release);
    closeSnack();
  };

  const [isQuickTourOpen, setIsQuickTourOpen] = useState(false);

  const saveViewed = () => {
    setIsQuickTourOpen(false);
    Storage.store("lastViewedVersion", release);
    setTimeout(() => {
      closeSnack();
    }, 250);
  };

  const learMore = e => {
    e.preventDefault();
    setIsQuickTourOpen(true);
  };

  return (
    <SnackbarContent ref={ref}>
      <Collapse in={!isQuickTourOpen} timeout="auto" unmountOnExit>
        <StyledDiv>
          <StyledH4>
            <FormattedMessage
              id="newInAresHeader"
              values={{
                version: release
              }}
            />
          </StyledH4>
          <StyledP>
            <FormattedMessage id="newInAresMessage" />
          </StyledP>
          <StyledSpan
            onClick={skipIt}
            onKeyDown={skipIt}
            role="button"
            tabIndex={0}
            data-component="skip-whats-new"
          >
            <FormattedMessage id="newInAresSkip" />
          </StyledSpan>
          <StyledButton
            role="button"
            tabIndex={0}
            onClick={learMore}
            onKeyDown={learMore}
            data-component="learn-more-whats-new"
          >
            <FormattedMessage id="newInAresButton" />
          </StyledButton>
        </StyledDiv>
      </Collapse>
      <QuickTour isOpen={isQuickTourOpen} onClose={saveViewed} />
    </SnackbarContent>
  );
});

export default Snackbar;

Snackbar.propTypes = {
  id: PropTypes.number.isRequired,
  revision: PropTypes.string.isRequired
};
