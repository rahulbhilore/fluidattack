import React, { Component } from "react";
import _ from "underscore";
import styled from "@material-ui/core/styles/styled";
import Radio from "@material-ui/core/Radio";
import PropTypes from "prop-types";

let StyledRadio = null;
let StyledIcon = null;
let StyledCheckedIcon = null;

/**
 * @class RadioButton
 * Customized radios as in
 * https://material-ui.com/components/radio-buttons/#customized-radios
 */
export default class RadioButton extends Component {
  static propTypes = {
    color: PropTypes.string,
    disabled: PropTypes.bool,
    value: PropTypes.string,
    radioButtonStyles: PropTypes.objectOf(PropTypes.string),
    radioIconStyles: PropTypes.objectOf(PropTypes.string),
    radioCheckedIconStyles: PropTypes.objectOf(PropTypes.string)
  };

  static defaultProps = {
    color: "secondary",
    radioButtonStyles: {},
    radioIconStyles: {},
    radioCheckedIconStyles: {},
    disabled: false,
    value: undefined
  };

  /**
   * @param styles
   * @private
   */
  static _buildStyles = (
    buttonStyles = {},
    iconStyles = {},
    checkedIconStyles = {}
  ) => {
    StyledRadio = styled(Radio)(() =>
      _.extend(
        {
          "&:hover": {
            backgroundColor: "transparent"
          },
          backgroundColor: "transparent !important",
          display: "inline-block",
          padding: "0 5px 0 0"
        },
        buttonStyles
      )
    );
    StyledIcon = styled("span")(({ theme }) =>
      _.extend(
        {
          borderRadius: "50%",
          width: 20,
          height: 20,
          border: `solid 1px ${theme.palette.DARK}`,
          backgroundColor: theme.palette.LIGHT,
          "input:disabled ~ &": {
            border: "none",
            background: "rgba(206,217,224,.5)"
          }
        },
        iconStyles
      )
    );

    StyledCheckedIcon = styled("span")(({ theme }) =>
      _.extend(
        _.extend(
          {
            borderRadius: "50%",
            width: 20,
            height: 20,
            border: `solid 1px ${theme.palette.DARK}`,
            backgroundColor: theme.palette.LIGHT,
            "input:disabled ~ &": {
              border: "none",
              background: "rgba(206,217,224,.5)"
            }
          },
          iconStyles
        ),
        _.extend(
          {
            backgroundColor: theme.palette.OBI,
            "&:before": {
              display: "block",
              width: 18,
              height: 18,
              backgroundImage: "radial-gradient(#fff,#fff 28%,transparent 32%)",
              content: '""'
            }
          },
          checkedIconStyles
        )
      )
    );
  };

  constructor(props) {
    super(props);
    const { radioButtonStyles, radioIconStyles, radioCheckedIconStyles } =
      props;
    RadioButton._buildStyles(
      radioButtonStyles,
      radioIconStyles,
      radioCheckedIconStyles
    );
  }

  componentDidUpdate(prevProps) {
    const { radioButtonStyles, radioIconStyles, radioCheckedIconStyles } =
      this.props;

    if (_.isEqual(radioButtonStyles, prevProps.radioButtonStyles)) return;
    if (_.isEqual(radioIconStyles, prevProps.radioIconStyles)) return;
    if (_.isEqual(radioCheckedIconStyles, prevProps.radioCheckedIconStyles))
      return;
    RadioButton._buildStyles(
      radioButtonStyles,
      radioIconStyles,
      radioCheckedIconStyles
    );
    this.forceUpdate();
  }

  render() {
    const { color, disabled, value } = this.props;

    return (
      <StyledRadio
        color={color}
        checkedIcon={<StyledCheckedIcon />}
        icon={<StyledIcon />}
        disabled={disabled}
        value={value}
      />
    );
  }
}
