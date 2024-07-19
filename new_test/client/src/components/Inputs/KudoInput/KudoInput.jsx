import React, { Component } from "react";
import clsx from "clsx";
import _ from "underscore";
import { styled } from "@material-ui/core";
import FormGroup from "@material-ui/core/FormGroup";
import * as InputPropTypes from "./InputPropTypes";
import StrengthChecker from "../StrengthChecker/StrengthChecker";
import StylingConstants from "../../../constants/appConstants/StylingConstants";
import FormManagerStore, {
  INPUT,
  INPUT_CHANGE_EVENT,
  CLEAR_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";
import HelperTextComponent from "./HelperTextComponent";
import InputComponent from "./InputComponent";
import LabelComponent from "./LabelComponent";

// default styles for material:
// label:{
//   marginBottom: theme.spacing(1),
//   color: theme.palette.OBI
// }

const StyledFormGroup = styled(FormGroup)(() => ({
  flexDirection: "row"
}));

/**
 * @class KudoInput
 * @classdesc Simple input. Should not be used for rendering select, checkbox, radio, button etc.
 * You can use prop "classes" to redefine styles of kudo input.
 * You must use material UI makeStyles() function for redefine original styles
 * example:
 * {
 *   formGroup: classes.formGroup,
 *   label: classes.label,
 *   input: classes.input
 *   helperText: classes.helperText
 * }
 */
export default class KudoInput extends Component {
  /**
   * @constant
   * @static
   */
  static propTypes = InputPropTypes.propTypes;

  /**
   * @constant
   * @static
   */
  static defaultProps = InputPropTypes.defaultProps;

  /**
   * @constructor
   * @param props
   */
  constructor(props) {
    super(props);
    let value = "";
    if (props.readOnly || props.disabled) {
      value = props.value || props.defaultValue;
    } else {
      value = props.defaultValue;
    }
    const valid = this.validateValue(value);
    this.state = {
      value,
      valid,
      inputId: props.id
    };
    if (props.formId !== "") {
      FormManagerStore.registerFormElement(
        props.formId,
        INPUT,
        props.id,
        props.name,
        value,
        valid
      );
    }
  }

  componentDidMount() {
    const { formId } = this.props;
    const { inputId } = this.state;
    if (formId) {
      FormManagerStore.on(
        INPUT_CHANGE_EVENT + formId + inputId,
        this.updateValue
      );
      FormManagerStore.on(CLEAR_EVENT + formId, this.clearInput);
    }
  }

  componentDidUpdate(prevProps) {
    const { isCheckOnExternalUpdate, readOnly, disabled, defaultValue, value } =
      this.props;
    if (
      (isCheckOnExternalUpdate && !_.isEqual(this.props, prevProps)) ||
      readOnly ||
      disabled
    ) {
      const { valid: previousValidState, value: stateValue } = this.state;
      const { onChange } = this.props;
      const newValue = defaultValue || value;
      const newValid = this.validateValue(newValue);
      if (stateValue !== newValue || previousValidState !== newValid) {
        // eslint-disable-next-line react/no-did-update-set-state
        this.setState(
          {
            value: newValue,
            valid: newValid
          },
          () => {
            if (onChange) {
              onChange(newValue, newValid);
            }
            if (newValid !== previousValidState || newValue.length === 0) {
              this.emitChangeAction();
            }
          }
        );
      }
    }
  }

  componentWillUnmount() {
    const { formId } = this.props;
    const { inputId } = this.state;
    if (formId) {
      FormManagerStore.removeListener(
        INPUT_CHANGE_EVENT + formId + inputId,
        this.updateValue
      );
      FormManagerStore.removeListener(CLEAR_EVENT + formId, this.clearInput);
    }
  }

  clearInput = () => {
    let newValue = "";
    const { readOnly, disabled, value, defaultValue } = this.props;
    if (readOnly || disabled) {
      newValue = value || defaultValue;
    } else {
      newValue = defaultValue;
    }
    this.setState(
      {
        value: newValue,
        valid: this.validateValue(newValue)
      },
      this.emitChangeAction
    );
  };

  /**
   * @description Set value from FormManagerStore
   */
  updateValue = () => {
    const { formId } = this.props;
    const { inputId, value, valid } = this.state;
    if (formId.length > 0) {
      const storeValue = FormManagerStore.getElementData(formId, inputId);
      if (value !== storeValue.value || valid !== storeValue.valid) {
        this.setState({
          value: storeValue.value,
          valid: storeValue.valid
        });
      }
    }
  };

  /**
   * @description Calculate validation state   *
   * @function
   * @private
   * @return {string|null}
   */
  calculateValidationState = () => {
    const { value, valid } = this.state;
    const { defaultValue } = this.props;
    if (value && value.length > 0 && defaultValue !== value) {
      if (valid === true) {
        return StylingConstants.SUCCESS;
      }
      return StylingConstants.ERROR;
    }
    return StylingConstants.NONE;
  };

  /**
   * @description Validate value according to the rules set
   * @function
   * @private
   * @nosideeffects
   * @param value {string}
   * @return {boolean}
   */
  validateValue = value => {
    const {
      defaultValue,
      restrictedValues,
      restrictedValuesCaseInsensitive,
      allowedValues,
      isStrengthCheck,
      isDefaultValueValid,
      isEmptyValueValid,
      validationFunction
    } = this.props;
    // if defaultValue === '' -> it should be checked as isEmptyValueValid
    if (defaultValue !== "" && value === defaultValue) {
      // check when it is default value
      return isDefaultValueValid;
    }
    if (value.length === 0) {
      // check when it is empty value
      return isEmptyValueValid;
    }
    if (
      !restrictedValuesCaseInsensitive &&
      restrictedValues.indexOf(value) > -1
    ) {
      // if value is restricted for case sensitive mode
      return false;
    }
    if (
      restrictedValuesCaseInsensitive &&
      restrictedValues.findIndex(
        elem => elem.toLowerCase() === value.toLowerCase()
      ) > -1
    ) {
      // if value is restricted for case insensitive mode
      return false;
    }
    if (allowedValues.indexOf(value) > -1) {
      return true;
    }
    if (isStrengthCheck) {
      return StrengthChecker.validateValue(value) >= 70;
    }
    if (validationFunction) {
      return validationFunction(value);
    }
    return false;
  };

  /**
   * @description Handle change of input
   * @function
   * @private
   * @param event {event}
   */
  handleChange = event => {
    const { value } = event.target;
    const { value: previousValue, valid: previousValidState } = this.state;
    if (value !== previousValue) {
      const newValid = this.validateValue(value);
      this.setState(
        {
          value,
          valid: newValid
        },
        () => {
          const { onChange, isEmitOnDefaultChange, defaultValue } = this.props;
          if (onChange) {
            onChange(value, newValid);
          }
          if (
            newValid !== previousValidState ||
            value.length === 0 ||
            previousValue.length === 0 ||
            (isEmitOnDefaultChange === true &&
              ((previousValue === defaultValue && value !== defaultValue) ||
                (previousValue !== defaultValue && value === defaultValue)))
          ) {
            this.emitChangeAction();
          }
        }
      );
    }
  };

  /**
   * @description Save changed value to store
   * @function
   * @private
   */
  emitChangeAction = e => {
    const { formId, onBlur, isHiddenValue } = this.props;
    const { inputId, value, valid } = this.state;
    if (
      formId.length > 0 &&
      FormManagerStore.checkIfElementIsRegistered(formId, inputId)
    ) {
      FormManagerActions.changeInputValue(
        formId,
        inputId,
        value,
        valid,
        isHiddenValue
      );
    }
    // called from onBlur will pass event
    if (e && onBlur) {
      onBlur(e, value, valid);
    }
  };

  // eslint-disable-next-line react/no-unused-class-component-methods
  getCurrentValue = () => {
    const { value } = this.state;
    return value;
  };

  // eslint-disable-next-line react/no-unused-class-component-methods
  getCurrentValidState = () => {
    const { valid } = this.state;
    return valid;
  };

  render() {
    const {
      label,
      helpMessage,
      showHelpBlock,
      isStrengthCheck,
      formGroupClassName,
      classes
    } = this.props;
    const { value, valid } = this.state;
    const validationState = this.calculateValidationState();
    const helpText = helpMessage(value, valid);

    return (
      <StyledFormGroup
        className={clsx(
          "kudoInputFormGroup",
          formGroupClassName,
          classes.formGroup
        )}
      >
        <LabelComponent
          className={classes.label}
          label={label}
          validationState={validationState}
        />
        <InputComponent
          // eslint-disable-next-line react/jsx-props-no-spreading
          {...this.props}
          emitChangeAction={this.emitChangeAction}
          handleChange={this.handleChange}
          value={value}
          maxLength={250}
          validationState={validationState}
        />
        {isStrengthCheck ? <StrengthChecker value={value} /> : null}
        {showHelpBlock ? (
          <HelperTextComponent
            className={classes.helperText}
            helpText={helpText}
            validationState={validationState}
          />
        ) : null}
      </StyledFormGroup>
    );
  }
}
