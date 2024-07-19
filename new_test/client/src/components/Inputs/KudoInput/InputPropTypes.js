import PropTypes from "prop-types";
import * as InputTypes from "../../../constants/appConstants/InputTypes";

export const propTypes = {
  /** @property {string} - name of input to use as a key * */
  name: PropTypes.string.isRequired,
  /** @property {string} - type of input * */
  type: PropTypes.oneOf(Object.values(InputTypes)).isRequired,
  /** @property {string} - html 'id' attribute * */
  id: PropTypes.string.isRequired,
  /** @property {string} - label to be shown along with the input.
   * If not set - label won't be shown.
   * Message ID should be provided!* */
  label: PropTypes.string,
  /** @property {string} - default value of input * */
  defaultValue: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  /** @property {boolean} - should default value be threatened as valid * */
  isDefaultValueValid: PropTypes.bool,
  /** @property {boolean} - should empty value be threatened as valid * */
  isEmptyValueValid: PropTypes.bool,
  /** @property {boolean} - values that should be threatened as invalid * */
  restrictedValues: PropTypes.arrayOf(PropTypes.string),
  /** @property {boolean} - if restricted values should be checked case insensitive */
  restrictedValuesCaseInsensitive: PropTypes.bool,
  /** @property {boolean} - values that should be threatened as valid.
   * Other values will be considered as invalid! * */
  allowedValues: PropTypes.arrayOf(PropTypes.string),
  /** @property {boolean} - should input be rendered inline or label should be above of input * */
  // isInline: PropTypes.bool,
  /** @property {string} - should input autoComplete be on or off * */
  autoComplete: PropTypes.oneOf(["on", "off"]),
  /** @property {boolean} - should input be focused by default or not * */
  autoFocus: PropTypes.bool,
  /** @property {number} - maximum length of input's value * */
  maxLength: PropTypes.number,
  /** @property {function} - function to be executed on input's value change * */
  onChange: PropTypes.func,
  /** @property {string} - input placeholder * */
  placeHolder: PropTypes.string,
  /** @property {boolean} - should input be disabled * */
  disabled: PropTypes.bool,
  /** @property {boolean} - should input be read-only * */
  readOnly: PropTypes.bool,
  /** @property {string} - id of form containing this input * */
  formId: PropTypes.string,
  /** @property {function} validation function -
   * should return true or false in response of value * */
  validationFunction: PropTypes.func,
  /** @property {boolean} if set to true - value won't be stored in logs * */
  isHiddenValue: PropTypes.bool,
  /** @property {boolean} if set to true - value will be checked using strengthChecker * */
  isStrengthCheck: PropTypes.bool,
  /** @property {boolean} if set to true - value will be rechecked when component will receive props update */
  isCheckOnExternalUpdate: PropTypes.bool,
  value: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  showHelpBlock: PropTypes.bool,
  helpMessage: PropTypes.func,
  /** @property {boolean} If true - change will be emitted for the first time value!==defValue */
  isEmitOnDefaultChange: PropTypes.bool,
  /** @property {function} Calls callback on blur */
  onBlur: PropTypes.func,
  onClick: PropTypes.func,
  min: PropTypes.number,
  max: PropTypes.number,
  required: PropTypes.bool,
  classes: PropTypes.objectOf(
    PropTypes.oneOfType([PropTypes.string, PropTypes.object])
  ),
  inputDataComponent: PropTypes.string
};

export const defaultProps = {
  label: "",
  className: "",
  defaultValue: "",
  isDefaultValueValid: false,
  isEmptyValueValid: false,
  restrictedValues: [],
  restrictedValuesCaseInsensitive: false,
  allowedValues: [],
  autoComplete: "on",
  autoFocus: false,
  maxLength: -1,
  onChange: null,
  placeHolder: "",
  disabled: false,
  readOnly: false,
  validationFunction: null,
  formId: "",
  isHiddenValue: false,
  isStrengthCheck: false,
  isCheckOnExternalUpdate: false,
  value: "",
  showHelpBlock: false,
  helpMessage: () => "",
  isEmitOnDefaultChange: false,
  onBlur: null,
  onClick: () => null,
  min: -Infinity,
  max: Infinity,
  required: false,
  classes: {},
  inputDataComponent: ""
};
