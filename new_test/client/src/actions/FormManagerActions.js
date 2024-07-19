import * as FormManagerConstants from "../constants/FormManagerConstants";
import AppDispatcher from "../dispatcher/AppDispatcher";

/**
 * @class
 * @classdesc Actions related to forms
 */
export default class FormManagerActions {
  /**
   * @method
   * @static
   * @public
   * @description Update form element value and validity
   * @param formId {string}
   * @param elementId {string}
   * @param value {*}
   * @param valid {boolean}
   * @param [hideValue] {boolean} - if true then value will be replaced with "*"
   */
  static changeInputValue(formId, elementId, value, valid, hideValue) {
    AppDispatcher.dispatch({
      actionType: FormManagerConstants.FM_CHANGE_INPUT_VALUE,
      formId,
      elementId,
      value,
      valid,
      hideValue
    });
  }

  /**
   * @function
   * @static
   * @public
   * @description Updates button disabled state (if isValid = false then disabled=true)
   * @param formId {string}
   * @param elementId {string}
   * @param isValid {boolean}
   */
  static changeButtonState(formId, elementId, isValid) {
    AppDispatcher.dispatch({
      actionType: FormManagerConstants.FM_CHANGE_BUTTON_STATE,
      formId,
      elementId,
      isValid
    });
  }

  static clearForm(formId) {
    AppDispatcher.dispatch({
      actionType: FormManagerConstants.FM_CLEAR_FORM,
      formId
    });
  }

  static submitForm(formId) {
    AppDispatcher.dispatch({
      actionType: FormManagerConstants.FM_SUBMIT_FORM,
      formId
    });
  }
}
