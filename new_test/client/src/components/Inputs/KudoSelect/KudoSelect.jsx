import React, { Component } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import { Shortcuts } from "react-shortcuts";
import { FormattedMessage } from "react-intl";
import Select from "@material-ui/core/Select";
import MenuItem from "@material-ui/core/MenuItem";
import InputBase from "@material-ui/core/InputBase";
import styled from "@material-ui/core/styles/styled";
import InputLabel from "@material-ui/core/InputLabel";
import FormControl from "@material-ui/core/FormControl";
import FormManagerStore, {
  SELECT,
  INPUT_CHANGE_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";

export default class KudoSelect extends Component {
  static propTypes = {
    label: PropTypes.string,
    defaultValue: PropTypes.string,
    options: PropTypes.objectOf(PropTypes.string.isRequired).isRequired,
    id: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    formId: PropTypes.string,
    disabled: PropTypes.bool,
    onChange: PropTypes.func,
    value: PropTypes.string,
    readOnly: PropTypes.bool,
    sortOptions: PropTypes.bool,
    styles: PropTypes.objectOf(PropTypes.string),
    classes: PropTypes.objectOf(PropTypes.string),
    dataComponent: PropTypes.string
  };

  static defaultProps = {
    label: "",
    defaultValue: "",
    formId: "",
    disabled: false,
    readOnly: false,
    value: "",
    onChange: () => null,
    sortOptions: false,
    styles: {},
    classes: {},
    dataComponent: ""
  };

  constructor(props) {
    super(props);
    let value = "";
    if (props.readOnly || props.disabled) {
      value = props.value || props.defaultValue;
    } else {
      value = props.defaultValue;
    }
    const valid = true;
    this.state = {
      value,
      valid,
      isOpen: false
    };
    if (props.formId !== "") {
      FormManagerStore.registerFormElement(
        props.formId,
        SELECT,
        props.id,
        props.name,
        value,
        valid
      );
    }

    this._styledComponents = {
      StyledFormControl: null,
      StyledLabel: null,
      StyledSelect: null,
      StyledInput: null,
      MenuProps: null
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
    const formControlStyles = styles?.formControl || {};
    const labelStyles = styles?.label || {};
    const selectStyles = styles?.select || {};
    const inputStyles = styles?.input || {};
    const menuProps = styles?.menuProps || {};

    this._styledComponents.StyledFormControl = styled(FormControl)(() =>
      _.extend(
        {
          width: "100%"
        },
        formControlStyles
      )
    );

    this._styledComponents.StyledLabel = styled(InputLabel)(({ theme }) =>
      _.extend(
        {
          position: "relative",
          color: theme.palette.OBI,
          marginBottom: theme.spacing(1),
          transform: "none"
        },
        labelStyles
      )
    );

    this._styledComponents.StyledSelect = styled(Select)(({ theme }) =>
      _.extend(
        {
          "& .MuiSelect-root": {
            color: theme.palette.CLONE,
            padding: "11px 0px 11px 12px"
          },
          "& .MuiSelect-icon": {
            color: theme.palette.CLONE
          }
        },
        selectStyles
      )
    );

    this._styledComponents.StyledInput = styled(InputBase)(({ theme }) =>
      _.extend(
        {
          borderRadius: 0,
          position: "relative",
          backgroundColor: theme.palette.LIGHT,
          border: `1px solid #ced4da`,
          color: "black",
          fontSize: 12,
          transition: theme.transitions.create(["border-color"]),
          "&:focus, &:hover": {
            borderColor: theme.palette.OBI
          }
        },
        inputStyles
      )
    );

    this._styledComponents.MenuProps = _.extend(
      {
        anchorOrigin: {
          vertical: "bottom",
          horizontal: "left"
        },
        transformOrigin: {
          vertical: "top",
          horizontal: "left"
        },
        getContentAnchorEl: null
      },
      menuProps
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
   * @param event {{value:string,label:string}}
   */
  handleChange = event => {
    const value = event.value || event.target.value;
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
    const { value, valid } = this.state;
    if (
      formId.length > 0 &&
      FormManagerStore.checkIfElementIsRegistered(formId, id)
    ) {
      FormManagerActions.changeInputValue(formId, id, value, valid, false);
    }
  };

  onKeyPress = action => {
    const { options, sortOptions, onChange, value, defaultValue } = this.props;

    const { value: stateValue, isOpen } = this.state;

    if (!isOpen) return;

    switch (action) {
      case "MOVE_UP":
      case "MOVE_DOWN": {
        const isUp = action === "MOVE_UP";
        let optionsKeys = Object.keys(options);
        if (sortOptions === true) {
          optionsKeys = optionsKeys.sort(key => options[key]);
        }

        const index = optionsKeys.findIndex(elem => elem === stateValue);

        if (isUp && index === 0) break;

        if (!isUp && optionsKeys.length === index + 1) break;

        this.setState({
          value: optionsKeys[index + (isUp ? -1 : 1)]
        });

        break;
      }
      case "SELECT": {
        const initialValue = value || defaultValue;

        if (initialValue === stateValue) break;

        this.setState(
          {
            isOpen: false
          },
          () => {
            if (onChange) {
              onChange(stateValue, true);
            }
            this.emitChangeAction();
          }
        );
        break;
      }
      default:
        break;
    }
  };

  render() {
    const {
      label,
      id,
      defaultValue,
      name,
      disabled,
      options,
      sortOptions,
      classes,
      dataComponent
    } = this.props;
    const { value, isOpen } = this.state;
    if (!options) return null;
    let optionsKeys = Object.keys(options);
    if (sortOptions === true) {
      optionsKeys = optionsKeys.sort(key => options[key]);
    }

    const {
      StyledFormControl,
      StyledLabel,
      StyledSelect,
      StyledInput,
      MenuProps
    } = this._styledComponents;

    return (
      <Shortcuts
        name="SELECT"
        handler={this.onKeyPress}
        global
        targetNodeSelector="body"
      >
        <StyledFormControl className={classes.formControl}>
          {label ? (
            <StyledLabel>
              <FormattedMessage id={label} />
            </StyledLabel>
          ) : null}
          <StyledSelect
            id={id}
            value={value || defaultValue}
            onChange={this.handleChange}
            onOpen={() =>
              this.setState({
                isOpen: true
              })
            }
            onClose={() =>
              this.setState({
                isOpen: false
              })
            }
            open={isOpen}
            name={name}
            disabled={disabled}
            input={<StyledInput />}
            MenuProps={MenuProps}
            data-component={dataComponent || "select"}
          >
            {optionsKeys.map(option => (
              <MenuItem
                key={option}
                value={option}
                data-component="option"
                data-value={option}
                data-label={options[option]}
              >
                {options[option]}
              </MenuItem>
            ))}
          </StyledSelect>
        </StyledFormControl>
      </Shortcuts>
    );
  }
}
