/**
 * Created by Dima Graebert on 5/30/2017.
 */
import EventEmitter from "events";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as FormManagerConstants from "../constants/FormManagerConstants";
import FormManagerActions from "../actions/FormManagerActions";

export const INPUT = "INPUT";
export const SELECT = "SELECT";
export const BUTTON = "BUTTON";
export const SWITCH = "SWITCH";
export const CHECKBOX = "CHECKBOX";
export const TREEVIEW = "TREEVIEW";
export const CHANGE_EVENT = "FM_CHANGE_EVENT";
export const INPUT_CHANGE_EVENT = "FM_INPUT_CHANGE_EVENT";
export const BUTTON_VALID_EVENT = "FM_BUTTON_VALID_EVENT";
export const SUBMIT_EVENT = "FM_SUBMIT_EVENT";
export const CLEAR_EVENT = "FM_CLEAR_EVENT";

/**
 * @class
 * @classdesc Contains all information about forms in application
 * @extends EventEmitter
 */
class FormManagerStore extends EventEmitter {
  constructor() {
    super();
    this.registeredForms = {};
    this.formsIds = [];
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  /**
   * @method
   * @public
   * @description Validates that the form is registered
   * @param formId {string}
   * @return {boolean}
   */
  checkIfFormIsRegistered(formId) {
    return this.formsIds.indexOf(formId) > -1;
  }

  /**
   * @method
   * @public
   * @description Validates that the element is registered in specified form
   * @param formId {string}
   * @param elementId {string}
   * @return {boolean}
   */
  checkIfElementIsRegistered(formId, elementId) {
    return !_.isUndefined(this.registeredForms[formId][elementId]);
  }

  /**
   * @method
   * @public
   * @description Register form
   * @param formId {string}
   * @param isCheckRequired {boolean}
   */
  registerForm(formId, isCheckRequired) {
    this.formsIds.push(formId);
    this.registeredForms[formId] = {
      isCheckRequired
    };
  }

  /**
   * @method
   * @public
   * @description Register form element
   * @throws Error - "not registered"
   * @param formId {string}
   * @param elementType {BUTTON|INPUT|SELECT|SWITCH|CHECKBOX|TREEVIEW}
   * @param elementId {string}
   * @param [name] {string}
   * @param [value] {string}
   * @param [valid] {boolean}
   */
  registerFormElement(formId, elementType, elementId, name, value, valid) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    let elementDescriptionObject;
    if (elementType === BUTTON) {
      elementDescriptionObject = {
        type: BUTTON,
        disabled: _.isUndefined(value) ? true : !!value
      };
    } else {
      if (!name) {
        throw new Error("Name should be passed for non-button elements!");
      }
      elementDescriptionObject = {
        type: elementType,
        name,
        value,
        valid
      };
    }
    this.registeredForms[formId][elementId] = elementDescriptionObject;
    this.emit(CHANGE_EVENT + formId);
  }

  /**
   * @method
   * @public
   * @description Update form element value and validity
   * @throws Error - "not registered"
   * @param formId {string}
   * @param elementId {string}
   * @param value {string}
   * @param valid {boolean}
   * @return {boolean} - true if valid state has changed, false - otherwise
   */
  setFormElementValues(formId, elementId, value, valid) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    if (!this.checkIfElementIsRegistered(formId, elementId)) {
      throw new Error(`Element with id ${elementId} isn't registered`);
    }
    this.registeredForms[formId][elementId].value = value;
    if (this.registeredForms[formId][elementId].valid !== valid) {
      this.registeredForms[formId][elementId].valid = valid;
      return true;
    }
    if (valid === false) {
      return false;
    }
    return this.registeredForms[formId].isCheckRequired === true;
  }

  /**
   * @method
   * @public
   * @description Updates button disabled state (if isValid = false then disabled=true)
   * @throws Error - "not registered"
   * @param formId {string}
   * @param elementId {string}
   * @param isValid {boolean}
   */
  changeButtonValidity(formId, elementId, isValid) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    if (!this.checkIfElementIsRegistered(formId, elementId)) {
      throw new Error(`Element with id ${elementId} isn't registered`);
    }
    this.registeredForms[formId][elementId].disabled = !isValid;
  }

  /**
   * @method
   * @public
   * @description Returns element data
   * @throws Error - "not registered"
   * @param formId {string}
   * @param elementId {string}
   * @return {{type:BUTTON|INPUT|SELECT|SWITCH|CHECKBOX|TREEVIEW,[value]:string,[valid]:boolean,[disabled]:boolean}}
   */
  getElementData(formId, elementId) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    if (!this.checkIfElementIsRegistered(formId, elementId)) {
      throw new Error(`Element with id ${elementId} isn't registered`);
    }
    return this.registeredForms[formId][elementId];
  }

  /**
   * @method
   * @public
   * @description Returns all form elements' data
   * @throws Error - "not registered"
   * @param formId
   * @return {{}}
   */
  getAllFormElementsData(formId) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    const inputs = _.where(this.registeredForms[formId], { type: INPUT });
    const selects = _.where(this.registeredForms[formId], { type: SELECT });
    const switches = _.where(this.registeredForms[formId], { type: SWITCH });
    const checkboxes = _.where(this.registeredForms[formId], {
      type: CHECKBOX
    });
    const treeViews = _.where(this.registeredForms[formId], { type: TREEVIEW });
    return _.indexBy(
      _.flatten([inputs, selects, switches, checkboxes, treeViews]),
      "name"
    );
  }

  /**
   * @method
   * @public
   * @description Returns button id for formId
   * @throws Error - "not registered"
   * @param formId
   * @return {string|undefined}
   */
  getButtonIdForForm(formId) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    return _.findKey(this.registeredForms[formId], { type: BUTTON });
  }

  /**
   * @method
   * @public
   * @description Returns status of form - true if it can be submitted and false otherwise
   * @throws Error - "not registered"
   * @param formId {string}
   * @return {boolean}
   */
  checkIfFormCanBeSubmitted(formId) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    const buttonInfo = _.find(this.registeredForms[formId], { type: BUTTON });
    if (buttonInfo) {
      return buttonInfo.disabled === false;
    }
    return true;
  }

  /**
   * @method
   * @private
   * @throws Error - "not registered"
   * @description Validate all form elements' values
   * @param formId {string}
   * @return {Array}
   */
  validateFormValues(formId) {
    if (!this.checkIfFormIsRegistered(formId)) {
      throw new Error(`Form with id ${formId} isn't registered`);
    }
    const elementKeys = Object.keys(this.registeredForms[formId]);
    return elementKeys.map(
      elementId =>
        new Promise(resolve => {
          const element = this.registeredForms[formId][elementId];
          if (!element?.type) resolve(false);
          else if (
            element.type === SELECT ||
            element.type === SWITCH ||
            element.type === CHECKBOX
          ) {
            resolve(true);
          } else if (element.type !== INPUT) {
            resolve(false);
          } else {
            const domElement = document.getElementById(elementId);
            if (
              domElement &&
              domElement.value &&
              domElement.value.length > 0 &&
              domElement.value !== element.value
            ) {
              FormManagerActions.changeInputValue(
                formId,
                elementId,
                domElement.value,
                element.valid,
                true
              );
              resolve(true);
            } else {
              resolve(false);
            }
          }
        })
    );
  }

  /**
   * @method
   * @description Handles all corresponding actions
   * @param action {{}}
   */
  handleAction(action) {
    if (action.actionType.indexOf(FormManagerConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case FormManagerConstants.FM_CHANGE_INPUT_VALUE:
          if (
            this.setFormElementValues(
              action.formId,
              action.elementId,
              action.value,
              action.valid
            )
          ) {
            this.emit(CHANGE_EVENT + action.formId);
            this.emit(INPUT_CHANGE_EVENT + action.formId + action.elementId);
          }
          break;
        case FormManagerConstants.FM_CHANGE_BUTTON_STATE:
          if (this.getButtonIdForForm(action.formId)) {
            this.changeButtonValidity(
              action.formId,
              action.elementId,
              action.isValid
            );
            this.emit(BUTTON_VALID_EVENT);
          }
          break;
        case FormManagerConstants.FM_SUBMIT_FORM:
          if (this.checkIfFormCanBeSubmitted(action.formId)) {
            Promise.all(this.validateFormValues(action.formId)).then(() => {
              this.emit(SUBMIT_EVENT + action.formId, action.formId);
            });
          }
          break;
        case FormManagerConstants.FM_CLEAR_FORM:
          this.emit(CLEAR_EVENT + action.formId);
          break;
        default:
          break;
      }
    }
  }
}

FormManagerStore.dispatchToken = null;
const formManagerStore = new FormManagerStore();
formManagerStore.setMaxListeners(0);

export default formManagerStore;
