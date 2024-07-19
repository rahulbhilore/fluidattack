import React, { Component } from "react";
import PropTypes from "prop-types";
import { Shortcuts } from "react-shortcuts";
import { styled } from "@material-ui/core";
import TreeView from "@material-ui/lab/TreeView";
import UserInfoStore from "../../../stores/UserInfoStore";
import ApplicationStore from "../../../stores/ApplicationStore";
import FormManagerStore, { TREEVIEW } from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";
import StylingConstants from "../../../constants/appConstants/StylingConstants";
import ListItem from "./ListItem";

const StyledTreeView = styled(TreeView)(() => ({
  width: "100%",
  listStyleType: "none",
  overflow: "hidden"
}));

const TREE_ROW_HEIGHT = 36;

/**
 * @class KudoTreeView
 * @classdesc TreeView
 */
export default class KudoTreeView extends Component {
  /**
   * @constant
   * @static
   */
  static propTypes = {
    /** @property {string} - name of input to use as a key * */
    name: PropTypes.string.isRequired,
    /** @property {string} - html 'id' attribute * */
    id: PropTypes.string.isRequired,
    /** @property {string} - additional css classes * */
    className: PropTypes.string,
    /** @property {string} - default value of input * */
    defaultValue: PropTypes.string,
    /** @property {function} - function to be executed on input's value change * */
    onChange: PropTypes.func,
    /** @property {string} - id of form containing this input * */
    formId: PropTypes.string,
    /** @property {{intl:function}} property passed by react-intl * */
    intl: PropTypes.shape({ formatMessage: PropTypes.func }),
    allowedTypes: PropTypes.arrayOf(PropTypes.string),
    isHiddenValue: PropTypes.bool,
    allowedExtensions: PropTypes.string,
    allFoldersAllowed: PropTypes.bool,
    storageType: PropTypes.string.isRequired,
    storageId: PropTypes.string.isRequired,
    baseFolderId: PropTypes.string,
    stopScrollTree: PropTypes.func,
    scrollContainer: PropTypes.node,
    customGetContent: PropTypes.func,
    customFoldCheck: PropTypes.func,
    disableSubfoldersOfSelected: PropTypes.bool,
    selectedObjects: PropTypes.arrayOf(PropTypes.string)
  };

  /**
   * @constant
   * @static
   */
  static defaultProps = {
    className: "",
    defaultValue: "",
    onChange: null,
    intl: { formatMessage: null },
    formId: "",
    allowedTypes: ["files", "folders"],
    isHiddenValue: false,
    allowedExtensions: "",
    allFoldersAllowed: false,
    baseFolderId: "-1",
    stopScrollTree: () => null,
    scrollContainer: null,
    customGetContent: null,
    customFoldCheck: null,
    disableSubfoldersOfSelected: false,
    selectedObjects: []
  };

  /**
   * @constructor
   * @param props
   */
  constructor(props) {
    super(props);
    const valid = this.validateValue(props.defaultValue || "");
    this.state = {
      value: props.defaultValue || "",
      valid,
      id: props.id
    };
    if (props.formId !== "") {
      FormManagerStore.registerFormElement(
        props.formId,
        TREEVIEW,
        props.id,
        props.name,
        props.defaultValue || "",
        valid
      );
    }
  }

  // eslint-disable-next-line react/no-unused-class-component-methods
  getFormData = () => this.state;

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

  /**
   * @description Set value from FormManagerStore
   */
  // eslint-disable-next-line react/no-unused-class-component-methods
  updateValue = () => {
    const { formId } = this.props;
    const { id, value, valid } = this.state;
    if (formId.length > 0) {
      const storeValue = FormManagerStore.getElementData(formId, id);
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
    let classNames = "kudoTreeView ";
    const { defaultValue, className } = this.props;
    const { value, valid } = this.state;
    if (value && value.length > 0 && defaultValue !== value) {
      // if user has entered something
      if (valid === true) {
        classNames += StylingConstants.SUCCESS;
      } else {
        classNames += StylingConstants.DANGER;
      }
      classNames += " ";
    }
    classNames += className;
    return classNames;
  };

  /**
   * @description Validate value according to the rules set
   * @function
   * @private
   * @nosideeffects
   * @param value {string}
   * @param [props] {Object}
   * @param [objectInfo] {Object}
   * @return {boolean}
   */
  validateValue = (value, props, objectInfo) => {
    const { defaultValue, isDefaultValueValid, validationFunction } =
      props || this.props;
    // if defaultValue === '' -> it should be checked as isEmptyValueValid
    if (defaultValue !== "" && value === defaultValue) {
      // check when it is default value
      return isDefaultValueValid;
    }
    if (value === undefined || value === null || value.length === 0) {
      // check when it is empty value
      return false;
    }
    if (validationFunction) {
      return validationFunction(value, objectInfo);
    }
    return true;
  };

  /**
   * @description Handle change of input
   * @function
   * @private
   * @param event {event|object}
   * @param [objectInfo] {object}
   */
  handleChange = (event, objectInfo) => {
    const value = Object.prototype.hasOwnProperty.call(event, "value")
      ? event.value
      : event.target.value;
    const { value: stateValue } = this.state;
    const checked = Object.prototype.hasOwnProperty.call(event, "checked")
      ? event.checked
      : true;
    if (value !== stateValue) {
      this.setState(
        {
          value,
          valid:
            checked === false
              ? false
              : this.validateValue(value, this.props, objectInfo)
        },
        () => {
          const { value: newStateValue, valid } = this.state;
          const { onChange } = this.props;
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
    const { id, value, valid } = this.state;
    if (
      formId.length > 0 &&
      FormManagerStore.checkIfElementIsRegistered(formId, id)
    ) {
      FormManagerActions.changeInputValue(
        formId,
        id,
        value,
        valid,
        isHiddenValue
      );
    }
  };

  keypressHandler = action => {
    const { stopScrollTree, scrollContainer } = this.props;

    const index = ListItem.onKeyPress(action);

    const { scrollHeight, scrollTop, clientHeight } = scrollContainer.current;
    if (scrollHeight === clientHeight) return;

    const topIndexOffset = Math.floor(scrollTop / TREE_ROW_HEIGHT);
    const bottomIndexOffset = Math.floor(
      (scrollHeight - (scrollTop + clientHeight)) / TREE_ROW_HEIGHT
    );

    const countOfVisibleIndexes = Math.floor(clientHeight / TREE_ROW_HEIGHT);

    const visibleWindowMiddleIndex =
      topIndexOffset + Math.floor(countOfVisibleIndexes / 2);

    switch (action) {
      case "MOVE_UP":
        if (index > visibleWindowMiddleIndex - 1 && topIndexOffset > 0)
          stopScrollTree(true);
        else stopScrollTree(false);
        break;
      case "MOVE_DOWN":
        if (index < visibleWindowMiddleIndex + 1 && bottomIndexOffset > 0)
          stopScrollTree(true);
        else stopScrollTree(false);
        break;
      default:
        break;
    }
  };

  render() {
    const areExternalStoragesAvailable = ApplicationStore.getApplicationSetting(
      "externalStoragesAvailable"
    );
    const {
      allowedTypes,
      allowedExtensions,
      allFoldersAllowed,
      storageType,
      storageId,
      baseFolderId,
      customFoldCheck,
      customGetContent,
      disableSubfoldersOfSelected,
      selectedObjects
    } = this.props;

    const { value } = this.state;
    return (
      <Shortcuts
        name="TREE_VIEW"
        handler={this.keypressHandler}
        global
        targetNodeSelector="body"
      >
        <StyledTreeView className={this.calculateClasses()}>
          <ListItem
            objectId={baseFolderId}
            customFoldCheck={customFoldCheck}
            customGetContent={customGetContent}
            objectName={
              areExternalStoragesAvailable
                ? UserInfoStore.getUserInfo("storage").name ||
                  UserInfoStore.getUserInfo("email")
                : "~"
            }
            parent={null}
            storageType={storageType}
            storageId={storageId}
            objectType="folder"
            icon={null}
            mimeType={null}
            unfolded
            offsetLevel={0}
            selectedFolder={value}
            handleChange={this.handleChange}
            allowedExtensions={allowedExtensions}
            validateValue={this.validateValue}
            allowedTypes={allowedTypes}
            allFoldersAllowed={allFoldersAllowed}
            disableSubfoldersOfSelected={disableSubfoldersOfSelected}
            selectedObjects={selectedObjects}
          />
        </StyledTreeView>
      </Shortcuts>
    );
  }
}
