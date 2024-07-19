import React, { Component } from "react";
import _ from "underscore";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import RadioGroup from "@material-ui/core/RadioGroup";
import FormControlLabel from "@material-ui/core/FormControlLabel";
import FormControl from "@material-ui/core/FormControl";
import FormLabel from "@material-ui/core/FormLabel";
import { styled, withStyles } from "@material-ui/core/styles";
import FormManagerStore, {
  SELECT,
  INPUT_CHANGE_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";
import RadioButton from "./RadioButton";

const baseRadioGroupTitleStyles = {
  fontSize: 12
};

const baseFormControlStyles = {
  marginBottom: 0,
  marginLeft: 0,
  height: 32
};

const baseOptionLabelStyles = {
  fontSize: 12
};

function OptionLabel(props) {
  const { classes, children } = props;

  return <span className={classes.root}>{children}</span>;
}

OptionLabel.propTypes = {
  classes: PropTypes.objectOf(PropTypes.string).isRequired,
  children: PropTypes.node.isRequired
};

/**
 * @return {function|null}
 */
function RadioGroupTitle(props) {
  const { classes, text } = props;

  return text.length ? (
    <FormLabel component="legend" className={classes.root}>
      <FormattedMessage id={text} />
    </FormLabel>
  ) : null;
}

RadioGroupTitle.propTypes = {
  classes: PropTypes.objectOf(PropTypes.string).isRequired,
  text: PropTypes.string.isRequired
};

let StyledFormControlLabel = null;
let StyledRadioGroupTitle = null;
let StyledOptionLabel = null;

export default class KudoRadio extends Component {
  /**
   * @description Validate value according to the rules set
   * @function
   * @private
   * @nosideeffects
   * @return {boolean}
   */
  static validateValue() {
    // TODO: consider adding validation?
    return true;
  }

  static propTypes = {
    label: PropTypes.string,
    className: PropTypes.string,
    defaultValue: PropTypes.string,
    options: PropTypes.objectOf(PropTypes.string.isRequired).isRequired,
    disabledOptions: PropTypes.arrayOf(PropTypes.string),
    id: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    formId: PropTypes.string,
    isHiddenValue: PropTypes.bool,
    value: PropTypes.string,
    onChange: PropTypes.func,
    inline: PropTypes.bool,
    disabled: PropTypes.bool,
    formControlLabelStyles: PropTypes.oneOfType([
      PropTypes.object,
      PropTypes.string
    ]),
    optionLabelStyles: PropTypes.oneOfType([
      PropTypes.object,
      PropTypes.string
    ]),
    radioGroupTitleStyles: PropTypes.oneOfType([
      PropTypes.object,
      PropTypes.string
    ]),
    radioButtonStyles: PropTypes.oneOfType([
      PropTypes.object,
      PropTypes.string
    ]),
    radioIconStyles: PropTypes.oneOfType([PropTypes.object, PropTypes.string]),
    radioCheckedIconStyles: PropTypes.oneOfType([
      PropTypes.object,
      PropTypes.string
    ]),
    dataComponent: PropTypes.string
  };

  static defaultProps = {
    label: "",
    className: "",
    defaultValue: "",
    formId: "",
    isHiddenValue: false,
    value: null,
    inline: true,
    onChange: () => null,
    disabledOptions: [],
    disabled: false,
    formControlLabelStyles: {},
    optionLabelStyles: {},
    radioGroupTitleStyles: {},
    radioButtonStyles: {},
    radioIconStyles: {},
    radioCheckedIconStyles: {},
    dataComponent: ""
  };

  constructor(props) {
    super(props);
    const value = props.value || props.defaultValue;
    const valid = KudoRadio.validateValue(value);
    this.state = {
      value,
      valid,
      inputId: props.id
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
    StyledFormControlLabel = styled(FormControlLabel)(
      _.extend(baseFormControlStyles, props.formControlLabelStyles)
    );
    StyledOptionLabel = withStyles({
      root: _.extend(baseOptionLabelStyles, props.optionLabelStyles)
    })(OptionLabel);
    StyledRadioGroupTitle = withStyles({
      root: _.extend(baseRadioGroupTitleStyles, props.radioGroupTitleStyles)
    })(RadioGroupTitle);
  }

  componentDidMount() {
    const { formId } = this.props;
    const { inputId } = this.state;
    if (formId) {
      FormManagerStore.on(
        INPUT_CHANGE_EVENT + formId + inputId,
        this.updateValue
      );
    }
  }

  componentDidUpdate(prevProps) {
    const { formControlLabelStyles, optionLabelStyles, radioGroupTitleStyles } =
      this.props;

    let needForceUpdate = false;

    if (!_.isEqual(formControlLabelStyles, prevProps.formControlLabelStyles)) {
      StyledFormControlLabel = styled(FormControlLabel)(
        _.extend(baseFormControlStyles, formControlLabelStyles)
      );
      needForceUpdate = true;
    }

    if (!_.isEqual(optionLabelStyles, prevProps.optionLabelStyles)) {
      StyledOptionLabel = withStyles({
        root: _.extend(baseOptionLabelStyles, optionLabelStyles)
      })(OptionLabel);
      needForceUpdate = true;
    }

    if (!_.isEqual(radioGroupTitleStyles, prevProps.radioGroupTitleStyles)) {
      StyledRadioGroupTitle = withStyles({
        root: _.extend(baseRadioGroupTitleStyles, radioGroupTitleStyles)
      })(RadioGroupTitle);
      needForceUpdate = true;
    }

    if (needForceUpdate) this.forceUpdate();
  }

  componentWillUnmount() {
    const { formId } = this.props;
    const { inputId } = this.state;
    if (formId) {
      FormManagerStore.removeListener(
        INPUT_CHANGE_EVENT + formId + inputId,
        this.updateValue
      );
    }
  }

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
   * @description Calculate css classes to be applied to input
   * @function
   * @private
   * @return {string}
   */
  calculateClasses = () => {
    let classNames = "kudoInput ";
    const { className } = this.props;
    classNames += className;
    classNames += " radioButtons";
    return classNames;
  };

  /**
   * @description Handle change of input
   * @function
   * @private
   * @param event {{value:string,label:string}}
   */
  handleChange = event => {
    const value = event.value || event.target.value;
    const { value: propValue } = this.props;
    const { value: stateValue } = this.state;
    if (value !== stateValue || (propValue && value !== propValue)) {
      this.setState(
        {
          value,
          valid: KudoRadio.validateValue(value)
        },
        () => {
          const { onChange } = this.props;
          const { value: newStateValue, valid } = this.state;
          if (onChange) {
            onChange(newStateValue, valid);
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
    const { formId, isHiddenValue } = this.props;
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
  };

  render() {
    const classNames = this.calculateClasses();
    const {
      label,
      name,
      options,
      disabledOptions,
      value: propValue,
      inline,
      disabled,
      radioButtonStyles,
      radioIconStyles,
      radioCheckedIconStyles,
      dataComponent
    } = this.props;
    const { inputId, value } = this.state;
    return (
      <div className="kudoInputFormGroup">
        <FormControl
          component="fieldset"
          className={classNames}
          disabled={disabled}
        >
          <StyledRadioGroupTitle text={label} />
          <RadioGroup
            aria-label={name}
            name={name}
            id={inputId}
            value={propValue || value}
            onChange={this.handleChange}
            row={inline}
            data-component={dataComponent}
          >
            {Object.keys(options).map(option => (
              <StyledFormControlLabel
                key={option}
                value={option}
                control={
                  <RadioButton
                    color="primary"
                    radioButtonStyles={radioButtonStyles}
                    radioIconStyles={radioIconStyles}
                    radioCheckedIconStyles={radioCheckedIconStyles}
                  />
                }
                label={<StyledOptionLabel>{options[option]}</StyledOptionLabel>}
                disabled={disabled || disabledOptions.includes(option)}
              />
            ))}
          </RadioGroup>
        </FormControl>
      </div>
    );
  }
}
