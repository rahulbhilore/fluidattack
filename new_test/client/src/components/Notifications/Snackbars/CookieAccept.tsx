import React, { forwardRef, useCallback, useMemo } from "react";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import { styled } from "@material-ui/core/styles";
import Storage from "../../../utils/Storage";
import ApplicationStore from "../../../stores/ApplicationStore";

const StyledDiv = styled("div")(({ theme }) => ({
  padding: "10px 10px 20px 10px",
  // @ts-ignore
  backgroundColor: theme.palette.VADER,
  width: "320px",
  // @ts-ignore
  color: theme.palette.LIGHT,
  textAlign: "left",
  fontSize: "12px",
  lineHeight: "20px",
  // @ts-ignore
  fontFamily: theme.kudoStyles.FONT_STACK
}));

const StyledA = styled("a")(({ theme }) => ({
  // @ts-ignore
  color: theme.palette.LIGHT,
  textDecoration: "underline",
  "&:hover, &:focus, &:visited": {
    // @ts-ignore
    color: theme.palette.LIGHT
  }
}));

const StyledButton = styled("button")(({ theme }) => ({
  padding: "8px 20px",
  width: "100%",
  textDecoration: "none",
  display: "block",
  // @ts-ignore
  backgroundColor: theme.palette.OBI,
  // @ts-ignore
  color: theme.palette.LIGHT,
  textTransform: "uppercase",
  fontWeight: 600,
  borderRadius: 0,
  // @ts-ignore
  borderColor: theme.palette.OBI,
  borderWidth: "1px",
  marginTop: "10px",
  textAlign: "center",
  "&:hover, &:focus, &:visited": {
    // @ts-ignore
    backgroundColor: theme.palette.OBI,
    // @ts-ignore
    borderColor: theme.palette.LIGHT,
    // @ts-ignore
    color: theme.palette.LIGHT,
    textTransform: "uppercase",
    cursor: "pointer"
  }
}));

type LinkProps = {
  text: string;
  link: string;
  dataComponent: string;
};

const GenericLink = React.memo(({ text, link, dataComponent }: LinkProps) => (
  <StyledA
    data-component={dataComponent}
    href={link}
    target="_blank"
    rel="noopener noreferrer"
  >
    {text}
  </StyledA>
));

type SnackProps = {
  id: string;
};

const Snackbar = forwardRef(
  (props: SnackProps, ref: React.ForwardedRef<HTMLDivElement>) => {
    const { closeSnackbar } = useSnackbar();

    const accept = useCallback(() => {
      Storage.store("CPAccepted", "true");
      closeSnackbar(props.id);
    }, [closeSnackbar]);

    const { termsOfUse, privacyPolicyLink } =
      ApplicationStore.getApplicationSetting("customization");

    const renderTermsLink = useCallback(
      (text: string) => (
        <GenericLink
          dataComponent="termsOfUseCookieLink"
          text={text}
          link={termsOfUse}
        />
      ),
      [termsOfUse]
    );
    const renderPrivacyPolicyLink = useCallback(
      (text: string) => (
        <GenericLink
          dataComponent="privacyPolicyCookieLink"
          text={text}
          link={privacyPolicyLink}
        />
      ),
      [privacyPolicyLink]
    );
    return (
      <SnackbarContent ref={ref}>
        <StyledDiv
          style={{
            color: `white`
          }}
        >
          <FormattedMessage
            id="cookieAgreement"
            values={{
              terms: renderTermsLink,
              privacy: renderPrivacyPolicyLink
            }}
          />
          <StyledButton
            role="button"
            tabIndex={0}
            onClick={accept}
            onKeyDown={accept}
            data-component="agreeToCookiesButton"
          >
            <FormattedMessage id="agreeAndClose" />
          </StyledButton>
        </StyledDiv>
      </SnackbarContent>
    );
  }
);

export default Snackbar;
