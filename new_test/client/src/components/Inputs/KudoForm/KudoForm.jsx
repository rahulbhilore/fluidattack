import React, { Component } from "react";
import _ from "underscore";
import { Shortcuts } from "react-shortcuts";
import PropTypes from "prop-types";
import FormManagerStore, {
  CHANGE_EVENT,
  SUBMIT_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";
import KudoButton from "../KudoButton/KudoButton";

export default class KudoForm extends Component {
  static propTypes = {
    id: PropTypes.string.isRequired,
    classNames: PropTypes.string,
    autoComplete: PropTypes.oneOf(["on", "off"]),
    onSubmitFunction: PropTypes.func,
    checkFunction: PropTypes.func,
    checkOnMount: PropTypes.bool,
    children: PropTypes.oneOfType([
      PropTypes.arrayOf(PropTypes.node),
      PropTypes.node
    ]).isRequired,
    enforceEnter: PropTypes.bool,
    autofocus: PropTypes.bool
  };

  static defaultProps = {
    classNames: "",
    autoComplete: "on",
    checkFunction: null,
    onSubmitFunction: null,
    checkOnMount: false,
    enforceEnter: false,
    autofocus: false
  };

  constructor(props) {
    super(props);
    this.state = {
      isSubmitted: false,
      isAllValid: false,
      shouldHandleEnterKey: true
    };
    const { id, checkFunction } = this.props;
    FormManagerStore.registerForm(id, checkFunction !== null);
    this.formRef = null;
  }

  componentDidMount() {
    const { children, id, checkOnMount, enforceEnter, autofocus } = this.props;
    if (!enforceEnter)
      React.Children.forEach(children, child => {
        if (
          child &&
          Object.prototype.hasOwnProperty.call(child, "type") &&
          Object.prototype.hasOwnProperty.call(child.type, "name") &&
          child.type.name === KudoButton.name
        ) {
          this.setState({
            shouldHandleEnterKey: false
          });
        }
      });
    FormManagerStore.on(CHANGE_EVENT + id, this.inputChanged);
    FormManagerStore.on(SUBMIT_EVENT + id, this.submitForm);
    if (checkOnMount) {
      setTimeout(() => {
        this.inputChanged();
      }, 0);
    }
    if (autofocus) {
      setTimeout(() => {
        this.formRef[0]?.focus();
      }, 0);
    }
  }

  componentWillUnmount() {
    const { id } = this.props;
    FormManagerStore.removeListener(CHANGE_EVENT + id, this.inputChanged);
    FormManagerStore.removeListener(SUBMIT_EVENT + id, this.submitForm);
  }

  /**
   * @method
   * @private
   * @description Fires on event come
   * @param action {string}
   */
  onKeyEvent = (action, event) => {
    const { isSubmitted, isAllValid, shouldHandleEnterKey } = this.state;
    const { id } = this.props;
    switch (action) {
      case "SUBMIT":
        if (
          isSubmitted === false &&
          isAllValid === true &&
          shouldHandleEnterKey === true
        ) {
          event.preventDefault();
          event.stopPropagation();
          FormManagerActions.submitForm(id);
          this.setState({ isSubmitted: true });
        }
        break;
      default:
        break;
    }
  };

  inputChanged = () => {
    const { id } = this.props;
    const formData = FormManagerStore.getAllFormElementsData(id);
    const isAllValid = this.checkIfAllElementsValid(formData);
    const submitButtonId = FormManagerStore.getButtonIdForForm(id);
    const { isAllValid: stateValid } = this.state;
    if (isAllValid !== stateValid) {
      FormManagerActions.changeButtonState(id, submitButtonId, isAllValid);
      this.setState({
        isAllValid
      });
    }
  };

  submitForm = () => {
    this.setState({ isSubmitted: false });
    const { id, onSubmitFunction } = this.props;
    const formData = FormManagerStore.getAllFormElementsData(id);
    if (onSubmitFunction !== null) {
      onSubmitFunction(formData);
    }
    return true;
  };

  checkIfAllElementsValid(formValues) {
    const isFormValid = _.every(formValues, element => element.valid === true);
    const { checkFunction } = this.props;
    return checkFunction !== null ? checkFunction(formValues) : isFormValid;
  }

  render() {
    const { id, classNames, autoComplete, children } = this.props;
    const { isSubmitted, isAllValid } = this.state;
    return (
      <Shortcuts name="FORM" handler={this.onKeyEvent} global alwaysFireHandler>
        <form
          onSubmit={event => {
            event.preventDefault();
            event.stopPropagation();
            if (isSubmitted === false && isAllValid === true) {
              FormManagerActions.submitForm(id);
              this.setState({ isSubmitted: true });
            }
          }}
          className={`${
            isAllValid ? "validForm" : "invalidForm"
          } ${classNames}`}
          autoComplete={autoComplete}
          ref={ref => {
            this.formRef = ref;
          }}
        >
          {children}
        </form>
      </Shortcuts>
    );
  }
}
