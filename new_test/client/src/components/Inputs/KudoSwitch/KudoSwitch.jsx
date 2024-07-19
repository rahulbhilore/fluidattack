import React, { Component } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import clsx from "clsx";
import withStyles from "@material-ui/core/styles/withStyles";
import styled from "@material-ui/core/styles/styled";
import FormGroup from "@material-ui/core/FormGroup";
import FormControlLabel from "@material-ui/core/FormControlLabel";
import Switch from "@material-ui/core/Switch";
import FormManagerStore, {
  SWITCH,
  INPUT_CHANGE_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";

export default class KudoSwitch extends Component {
  static propTypes = {
    label: PropTypes.string,
    className: PropTypes.string,
    id: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    defaultChecked: PropTypes.bool,
    reverse: PropTypes.bool,
    checkedChildren: PropTypes.oneOfType([PropTypes.string, PropTypes.node]),
    unCheckedChildren: PropTypes.oneOfType([PropTypes.string, PropTypes.node]),
    formId: PropTypes.string,
    onChange: PropTypes.func,
    iconSrc: PropTypes.string,
    disabled: PropTypes.bool,
    styles: PropTypes.objectOf(
      PropTypes.objectOf(
        PropTypes.oneOfType([PropTypes.string, PropTypes.number])
      )
    ),
    translateLabel: PropTypes.bool,
    dataComponent: PropTypes.string
  };

  static defaultProps = {
    label: "",
    className: "",
    formId: "",
    defaultChecked: false,
    reverse: false,
    checkedChildren: "",
    unCheckedChildren: "",
    onChange: () => null,
    iconSrc: "",
    disabled: false,
    styles: {},
    translateLabel: true,
    dataComponent: ""
  };

  constructor(props) {
    super(props);
    this.state = {
      value: props.defaultChecked,
      valid: true
    };
    if (props.formId !== "") {
      FormManagerStore.registerFormElement(
        props.formId,
        SWITCH,
        props.id,
        props.name,
        props.defaultChecked,
        true
      );
    }

    this._styledComponents = {
      StyledFormGroup: null,
      StyledLabel: null,
      StyledSwitch: null,
      StyledIcon: null
    };

    this._buildStyles(props.styles);
  }

  componentDidMount() {
    const { formId, id } = this.props;
    if (formId) {
      FormManagerStore.on(INPUT_CHANGE_EVENT + formId + id, this.updateValue);
    }
  }

  componentDidUpdate(prevProps) {
    const { styles } = this.props;
    if (_.isEqual(styles, prevProps.styles)) return;
    this._buildStyles(styles);
    this.forceUpdate();
  }

  componentWillUnmount() {
    const { formId, id } = this.props;
    if (formId) {
      FormManagerStore.removeListener(
        INPUT_CHANGE_EVENT + formId + id,
        this.updateValue
      );
    }
  }

  _buildStyles = styles => {
    const formGroupStyles = styles?.formGroup || {};
    const labelStyles = styles?.label || {};
    const iconStyles = styles?.icon || {};
    const switchStyles = styles?.switch || {};

    const { className } = this.props;

    this._styledComponents.StyledFormGroup = withStyles(() => ({
      root: _.extend(
        {
          display: "inline-block",
          width: "100%",
          height: "36px",
          margin: "-8px 0 8px 0"
        },
        formGroupStyles
      )
    }))(props => {
      const { classes, children } = props;
      return (
        <FormGroup
          role="group"
          className={className ? clsx(className, classes.root) : classes.root}
          // eslint-disable-next-line react/jsx-props-no-spreading
          {...props}
        >
          {children}
        </FormGroup>
      );
    });

    this._styledComponents.StyledLabel = styled(FormControlLabel)(({ theme }) =>
      _.extend(
        {
          marginLeft: 4,
          justifyContent: "space-between",
          width: "90%",
          fontSize: theme.typography.pxToRem(12),
          pointerEvents: "none",
          "&.Mui-disabled": {
            cursor: "not-allowed"
          },
          "&.Mui-disabled .MuiTypography-root": {
            color: theme.palette.REY
          }
        },
        labelStyles
      )
    );

    this._styledComponents.StyledSwitch = styled(Switch)(({ theme }) =>
      _.extend(
        {
          width: "44px",
          height: "26px",
          padding: 0,
          margin: theme.spacing(1),
          pointerEvents: "all",
          "& .MuiSwitch-switchBase": {
            padding: 1,
            color: theme.palette.LIGHT,
            top: "3px",
            left: "6px"
          },
          "& .Mui-checked": {
            transform: "translateX(12px)"
          },
          "& .MuiSwitch-track": {
            borderRadius: 50,
            opacity: 1,
            backgroundColor: theme.palette.REY
          },
          "& .MuiSwitch-thumb": {
            width: 18,
            height: 18
          },
          "& .Mui-checked + .MuiSwitch-track": {
            padding: 1,
            opacity: 1,
            backgroundColor: theme.palette.OBI
          },
          "& .MuiSwitch-input": {
            margin: 0
          },
          "& .Mui-disabled + .MuiSwitch-track": {
            backgroundColor: theme.palette.REY,
            opacity: 1
          },
          "& .Mui-disabled.MuiSwitch-switchBase": {
            color: theme.palette.secondary.light,
            opacity: 0.5
          }
          // "& .Mui-disabled .MuiSwitch-switchBase": {
          //   color: theme.palette.secondary.light,
          //   opacity: 0.5
          // }
        },
        switchStyles
      )
    );

    this._styledComponents.StyledIcon = styled("img")(() =>
      _.extend(
        {
          width: 20,
          height: 20,
          marginTop: 10
        },
        iconStyles
      )
    );
  };

  /**
   * @description Set value from FormManagerStore
   */
  updateValue = () => {
    const { formId, id } = this.props;
    if (formId.length > 0) {
      const storeValue = FormManagerStore.getElementData(formId, id);
      const { value, valid } = this.state;
      if (value !== storeValue.value || valid !== storeValue.valid) {
        this.setState({
          value: storeValue.value,
          valid: storeValue.valid
        });
      }
    }
  };

  /**
   * @description Handle change of input
   * @function
   * @private
   * @param event {boolean}
   */
  handleChange = event => {
    const value = event.target.checked;
    const { value: stateValue } = this.state;
    const { onChange } = this.props;
    if (value !== stateValue) {
      this.setState(
        {
          value,
          valid: true
        },
        () => {
          if (onChange) {
            onChange(value, true);
          }
          this.emitChangeAction();
        }
      );
    }
  };

  /**
   * @description Save changed value to store
   * @function
   * @private
   */
  emitChangeAction = () => {
    const { formId, id } = this.props;
    if (
      formId.length > 0 &&
      FormManagerStore.checkIfElementIsRegistered(formId, id)
    ) {
      const { value, valid } = this.state;
      FormManagerActions.changeInputValue(formId, id, value, valid, false);
    }
  };

  render() {
    const {
      name,
      iconSrc,
      reverse,
      label,
      disabled,
      translateLabel,
      dataComponent
    } = this.props;
    const { StyledFormGroup, StyledSwitch, StyledLabel, StyledIcon } =
      this._styledComponents;
    const { value } = this.state;
    let labelComponent = null;
    if (label) {
      if (translateLabel) {
        labelComponent = <FormattedMessage id={label} />;
      } else {
        labelComponent = label;
      }
    }
    return (
      <StyledFormGroup>
        {iconSrc ? <StyledIcon src={iconSrc} /> : null}
        <StyledLabel
          labelPlacement={reverse ? "end" : "start"}
          role="switch"
          control={
            <StyledSwitch
              checked={value}
              onChange={this.handleChange}
              name={name}
              disabled={disabled}
              data-component={dataComponent}
            />
          }
          label={labelComponent}
        />
      </StyledFormGroup>
    );
  }
}
