import React, { Component } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import { CircularProgress } from "@mui/material";
import _ from "underscore";
import clsx from "clsx";
import Button from "@material-ui/core/Button";
import withStyles from "@material-ui/core/styles/withStyles";
import styled from "@material-ui/core/styles/styled";
import Typography from "@material-ui/core/Typography";
import FormManagerStore, {
  BUTTON,
  BUTTON_VALID_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";

export default class KudoButton extends Component {
  static propTypes = {
    isSubmit: PropTypes.bool,
    isDisabled: PropTypes.bool,
    isLoading: PropTypes.bool,
    loadingLabelId: PropTypes.string,
    children: PropTypes.node.isRequired,
    className: PropTypes.string,
    /** @property {string} - id of form realted to this button * */
    formId: PropTypes.string,
    id: PropTypes.string,
    onClick: PropTypes.func,
    styles: PropTypes.oneOfType([PropTypes.object, PropTypes.string]),
    dataComponent: PropTypes.string,
    useRightPadding: PropTypes.bool
  };

  static defaultProps = {
    isSubmit: false,
    isDisabled: true,
    isLoading: false,
    loadingLabelId: "loading",
    formId: "",
    id: "",
    onClick: null,
    styles: {},
    className: "",
    dataComponent: "",
    useRightPadding: false
  };

  constructor(props) {
    super(props);
    this.state = {
      disabled: props.isDisabled,
      isLoading: props.isLoading,
      buttonId: props.id || `formButton_${props.formId}`
    };
    if (props.formId !== "" && props.isSubmit === true) {
      FormManagerStore.registerFormElement(
        props.formId,
        BUTTON,
        props.id || `formButton_${props.formId}`,
        null,
        props.isDisabled
      );
    }
    this._styledComponents = {
      StyledButton: null,
      StyledTypography: null
    };

    this._buildStyles(props.styles);
  }

  componentDidMount() {
    const { formId } = this.props;
    if (formId !== "") {
      FormManagerStore.on(BUTTON_VALID_EVENT, this.updateDisabledState);
    }
  }

  componentDidUpdate(prevProps) {
    const { styles, isLoading } = this.props;
    if (
      _.isEqual(styles, prevProps.styles) &&
      isLoading === prevProps.isLoading
    )
      return;
    this.setState(prev => ({
      ...prev,
      isLoading
    }));
    this._buildStyles(styles);
    this.forceUpdate();
  }

  componentWillUnmount() {
    FormManagerStore.removeListener(
      BUTTON_VALID_EVENT,
      this.updateDisabledState
    );
  }

  _buildStyles = styles => {
    const buttonStyles = styles?.button || {};
    const typographyStyled = styles?.typography || {};

    const { className, useRightPadding } = this.props;

    this._styledComponents.StyledButton = withStyles(theme => ({
      root: _.extend(
        {
          marginRight: theme.spacing(useRightPadding ? 2 : 0),
          backgroundColor: `${theme.palette.OBI}!important`,
          transition: theme.transitions.create(["opacity"]),
          padding: "10px 30px",
          textTransform: "uppercase",
          "&.Mui-disabled": {
            opacity: 0.65
          }
        },
        buttonStyles
      )
    }))(props => {
      const { classes, children } = props;
      return (
        <Button
          className={className ? clsx(className, classes.root) : classes.root}
          disableFocusRipple
          // eslint-disable-next-line react/jsx-props-no-spreading
          {...props}
        >
          {children}
        </Button>
      );
    });

    this._styledComponents.StyledTypography = styled(Typography)(({ theme }) =>
      _.extend(
        {
          fontWeight: 900,
          color: `${theme.palette.LIGHT}!important`,
          fontSize: theme.typography.pxToRem(12)
        },
        typographyStyled
      )
    );
  };

  updateDisabledState = () => {
    const { formId } = this.props;
    const { buttonId, disabled: stateDisabled } = this.state;
    const { disabled } = FormManagerStore.getElementData(formId, buttonId);
    if (disabled !== stateDisabled) {
      this.setState({
        disabled
      });
    }
  };

  handleClick = event => {
    const { isSubmit, formId, onClick } = this.props;
    if (isSubmit === true && formId !== "") {
      event.preventDefault();
      FormManagerActions.submitForm(formId);
    } else if (onClick) {
      onClick(event);
    }
  };

  render() {
    const { isSubmit, children, dataComponent, loadingLabelId } = this.props;
    const { disabled, isLoading } = this.state;
    const { StyledButton, StyledTypography } = this._styledComponents;

    return (
      <StyledButton
        type={isSubmit ? "submit" : "button"}
        disabled={disabled}
        onClick={this.handleClick}
        data-component={isSubmit ? "submit-button" : dataComponent}
      >
        <StyledTypography>
          {isLoading ? (
            <>
              <FormattedMessage id={loadingLabelId} />
              <CircularProgress
                size={36}
                thickness={5}
                sx={{
                  position: "absolute",
                  top: "50%",
                  left: "50%",
                  marginTop: "-18px",
                  marginLeft: "-18px"
                }}
              />
            </>
          ) : (
            children
          )}
        </StyledTypography>
      </StyledButton>
    );
  }
}
