import React, { Component } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import clsx from "clsx";
import styled from "@material-ui/core/styles/styled";
import withStyles from "@material-ui/core/styles/withStyles";
import CheckIcon from "@material-ui/icons/Check";
import Checkbox from "@material-ui/core/Checkbox";
import FormGroup from "@material-ui/core/FormGroup";
import FormControlLabel from "@material-ui/core/FormControlLabel";
import { FormControl, FormHelperText } from "@material-ui/core";
import FormManagerStore, {
  CHECKBOX,
  INPUT_CHANGE_EVENT
} from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";

export default class KudoCheckbox extends Component {
  static propTypes = {
    isRequired: PropTypes.bool,
    label: PropTypes.string,
    className: PropTypes.string,
    id: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    checked: PropTypes.bool,
    reverse: PropTypes.bool,
    formId: PropTypes.string,
    onChange: PropTypes.func,
    disabled: PropTypes.bool,
    styles: PropTypes.objectOf(PropTypes.string),
    helperText: PropTypes.string
  };

  static defaultProps = {
    isRequired: false,
    label: "",
    formId: "",
    checked: false,
    reverse: false,
    onChange: () => null,
    disabled: false,
    styles: {},
    className: "",
    helperText: ""
  };

  constructor(props) {
    super(props);
    const valid = props.isRequired ? props.checked === true : true;
    this.state = {
      value: props.checked,
      valid
    };
    if (props.formId !== "") {
      FormManagerStore.registerFormElement(
        props.formId,
        CHECKBOX,
        props.id,
        props.name,
        props.checked,
        valid
      );
    }
    this._styledComponents = {
      StyledFormGroup: null,
      StyledFormControlLabel: null,
      StyledCheckbox: null,
      StyledCheckedIcon: null,
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
    const formControlLabelStyles = styles?.controlLabel || {};
    const checkboxStyles = styles?.checkbox || {};
    const checkedIconStyles = styles?.checkedIcon || {};
    const checkedIcon = styles?.icon || {};
    const { className } = this.props;

    this._styledComponents.StyledFormGroup = withStyles({
      root: formGroupStyles
    })(props => {
      const { classes } = props;
      return (
        <FormGroup
          className={className ? clsx(className, classes.root) : classes.root}
          // eslint-disable-next-line react/jsx-props-no-spreading
          {...props}
        />
      );
    });

    this._styledComponents.StyledFormControlLabel = styled(FormControlLabel)(
      formControlLabelStyles
    );

    this._styledComponents.StyledCheckbox = styled(Checkbox)(({ theme }) =>
      _.extend(
        {
          "& .MuiSvgIcon-root": {
            width: "25px",
            height: "25px"
          },
          "&.MuiCheckbox-root": {
            color: theme.palette.CLONE
          },
          "&.Mui-checked": {
            color: theme.palette.OBI
          },
          "&.Mui-disabled .MuiSvgIcon-root": {
            backgroundColor: theme.palette.CLONE
          }
        },
        checkboxStyles
      )
    );

    this._styledComponents.StyledCheckedIcon = styled(CheckIcon)(({ theme }) =>
      _.extend(
        {
          backgroundColor: theme.palette.OBI,
          color: theme.palette.LIGHT,
          transition: theme.transitions.create(["background-color"]),
          "&.MuiSvgIcon-root": {
            width: "16px",
            height: "16px"
          },
          "input:hover ~ &": {
            backgroundColor: "#123a76" // slightly darker OBI
          }
        },
        checkedIconStyles
      )
    );

    this._styledComponents.StyledIcon = styled("span")(({ theme }) =>
      _.extend(
        {
          borderRadius: 0,
          width: "16px",
          height: "16px",
          backgroundColor: theme.palette.LIGHT,
          border: `1px solid ${theme.palette.REY}`,
          transition: theme.transitions.create(["background-color"]),
          "input:hover ~ &": {
            backgroundColor: theme.palette.REY
          },
          "input:disabled ~ &": {
            boxShadow: "none",
            background: theme.palette.CLONE
          }
        },
        checkedIcon
      )
    );
  };

  /**
   * @description Set value from FormManagerStore
   */
  updateValue = () => {
    const { formId, id } = this.props;
    const { value, valid } = this.state;
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
   * @description Handle change of input
   * @function
   * @private
   */
  handleChange = () => {
    const { value } = this.state;
    const { isRequired, onChange } = this.props;
    const valid = isRequired ? value === false : true;
    this.setState(
      {
        value: !value,
        valid
      },
      () => {
        if (onChange) {
          onChange(value, valid);
        }
        this.emitChangeAction();
      }
    );
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

  render() {
    const { value } = this.state;
    const { name, disabled, id, reverse, label, helperText } = this.props;
    const {
      StyledFormGroup,
      StyledFormControlLabel,
      StyledCheckbox,
      StyledCheckedIcon,
      StyledIcon
    } = this._styledComponents;
    return (
      <StyledFormGroup aria-label="position" row>
        <FormControl>
          <StyledFormControlLabel
            control={
              <StyledCheckbox
                id={id}
                name={name}
                checked={value}
                disabled={disabled}
                onChange={this.handleChange}
                checkedIcon={<StyledCheckedIcon />}
                icon={<StyledIcon />}
                disableRipple
              />
            }
            label={label ? <FormattedMessage id={label} /> : null}
            labelPlacement={reverse ? "end" : "start"}
          />
          {helperText.length > 0 ? (
            <FormHelperText>
              <FormattedMessage id={helperText} />
            </FormHelperText>
          ) : null}
        </FormControl>
      </StyledFormGroup>
    );
  }
}
