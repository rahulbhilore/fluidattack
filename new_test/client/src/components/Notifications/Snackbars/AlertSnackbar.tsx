import React, { forwardRef, useCallback } from "react";
import { SnackbarContent, useSnackbar } from "notistack";
import _ from "underscore";
import { styled } from "@material-ui/core";
import { FormattedMessage } from "react-intl";
import NotificationsIcon from "@material-ui/icons/Notifications";
import closeIcon from "../../../assets/images/Close.svg";
import MainFunctions from "../../../libraries/MainFunctions";
import { TranslationKey } from "../../../libraries/PageLoad";

const findColors = (type: string) => {
  switch (type) {
    case "success":
    case "ok": {
      return {
        background: "#dff0d8",
        border: "#d6e9c6"
      };
    }
    case "error": {
      return {
        background: "#f2dede",
        border: "#f2dede"
      };
    }
    case "info": {
      return {
        background: "#d9edf7",
        border: "#bce8f1"
      };
    }
    case "warning": {
      return {
        background: "#fcf8e3",
        border: "#faebcc"
      };
    }
    default: {
      return {
        background: "#f2dede",
        border: "#f2dede"
      };
    }
  }
};

let StyledDivBlock = null;

const StyledTitleDiv = styled("div")(() => ({
  textTransform: "uppercase",
  fontWeight: "bold"
}));

const StyledContentDiv = styled("div")(() => ({
  marginTop: "22px",
  "& p::first-letter": {
    textTransform: "capitalize"
  }
}));

const StyledButton = styled("button")(() => ({
  border: "none",
  float: "right",
  backgroundColor: "transparent",
  padding: 0,
  cursor: "pointer"
}));

const StyledImage = styled("img")(() => ({
  width: "14px"
}));

const MessageWrapper = styled("p")(() => ({
  whiteSpace: "pre-line"
}));

type Props = {
  id: number;
  type: string;
  message: string | Record<string | TranslationKey, string>;
};

const Snackbar = forwardRef<HTMLDivElement, Props>((props, ref) => {
  const { id, type, message }: Props = props;

  const { closeSnackbar } = useSnackbar();

  const close = useCallback(() => {
    closeSnackbar(id);
  }, [closeSnackbar]);

  const { background, border } = findColors(type);

  StyledDivBlock = styled("div")(({ theme }) => ({
    marginTop: "10px",
    padding: "15px",
    backgroundColor: background,
    border: `1px solid ${border}`,
    width: "32vw",
    color: theme.palette.secondary.main,
    fontSize: "12px",
    lineHeight: "20px",
    // @ts-ignore
    fontFamily: theme.kudoStyles.FONT_STACK,
    cursor: "pointer",
    [theme.breakpoints.down("sm")]: {
      width: "100%"
    }
  }));

  let alertContent;

  if (_.isArray(message)) {
    alertContent = message.map(chunk => (
      <MessageWrapper key={MainFunctions.getStringHashCode(chunk.id || chunk)}>
        {_.isString(chunk) ? (
          chunk
        ) : (
          <FormattedMessage id={chunk.id} values={{ ..._.omit(chunk, "id") }} />
        )}
      </MessageWrapper>
    ));
  } else if (_.isObject(message) && message.id) {
    alertContent = (
      <MessageWrapper>
        <FormattedMessage
          id={message.id}
          values={{ ..._.omit(message, "id") }}
        />
      </MessageWrapper>
    );
  } else {
    alertContent = <MessageWrapper>{message.toString()}</MessageWrapper>;
  }

  return (
    <SnackbarContent ref={ref}>
      <StyledDivBlock data-component="alert" data-status={type} onClick={close}>
        <StyledTitleDiv>
          <NotificationsIcon
            style={{
              marginRight: "8px",
              verticalAlign: "middle",
              fontSize: "1.2rem"
            }}
          />
          <FormattedMessage id={type} />
          <StyledButton>
            <StyledImage src={closeIcon} alt="" />
          </StyledButton>
        </StyledTitleDiv>
        <StyledContentDiv className="alertText">
          {alertContent}
        </StyledContentDiv>
      </StyledDivBlock>
    </SnackbarContent>
  );
});

export default Snackbar;
