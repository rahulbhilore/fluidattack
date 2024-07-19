/* eslint-disable class-methods-use-this */
import React, { Component } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import Tooltip from "@material-ui/core/Tooltip";
import { styled } from "@material-ui/core/styles";
import LinearProgress from "@material-ui/core/LinearProgress";
import UserInfoStore, { STORAGES_UPDATE } from "../../stores/UserInfoStore";

const BAR_STATE0 = 0;
const BAR_STATE1 = 1;
const BAR_STATE2 = 2;

const BAR_STATE1_TRIGGER = 33;
const BAR_STATE2_TRIGGER = 66;

const MIN_VISIBLE_VALUE = 3;

let StyledProgressBar = null;

export default class StorageSizeBar extends Component {
  static propTypes = {
    storage: PropTypes.string.isRequired
  };

  constructor(props) {
    super(props);

    this.state = {
      value: null,
      barState: null
    };
    this.buildStyles(BAR_STATE0);
  }

  componentDidMount() {
    UserInfoStore.addChangeListener(STORAGES_UPDATE, this.onStorageUpdate);
    this.onStorageUpdate();
  }

  shouldComponentUpdate(nextProps, nextState) {
    const { barState: currentBarState, value: currentValue } = this.state;
    const { barState: nextBarState, value: nextValue } = nextState;

    if (currentBarState === nextBarState && currentValue === nextValue)
      return false;

    this.buildStyles(nextBarState);

    return true;
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(STORAGES_UPDATE, this.onStorageUpdate);
  }

  buildStyles = barState => {
    StyledProgressBar = styled(LinearProgress)(({ theme }) => {
      let color = null;

      switch (barState) {
        case BAR_STATE0: {
          color = theme.palette.YODA;
          break;
        }
        case BAR_STATE1: {
          color = theme.palette.YELLOW_BUTTON;
          break;
        }
        case BAR_STATE2: {
          color = theme.palette.KYLO;
          break;
        }
        default: {
          color = theme.palette.YODA;
        }
      }

      return {
        backgroundColor: "transparent",
        height: 9,
        borderRadius: "0px",
        margin: "0px 0px",
        border: `2px solid ${theme.palette.SNOKE}`,
        "&.MuiLinearProgress-colorPrimary": {
          backgroundColor: theme.palette.CLONE
        },
        "& .MuiLinearProgress-bar": {
          borderRadius: "0px",
          borderRight: `2px solid ${theme.palette.SNOKE}`
        },
        "& .MuiLinearProgress-barColorPrimary": {
          backgroundColor: color
        }
      };
    });
  };

  calculateValue = (currentSize, fullSize) => {
    if ((!currentSize && currentSize !== 0) || !fullSize) return null;
    const value = Math.ceil((currentSize / fullSize) * 100);
    return value < 0 ? 0 : value;
  };

  onStorageUpdate = () => {
    const { storage } = this.props;
    const currentStorage = UserInfoStore.getStoragesData()?.[storage];
    if (!currentStorage) return;

    const { quota, usage } = _.first(currentStorage);
    let value = this.calculateValue(usage, quota);

    if (value > 100) value = 100;

    this.setState({
      value,
      barState: this.calculateBarState(value)
    });
  };

  calculateBarState = value => {
    if (value < BAR_STATE1_TRIGGER) return BAR_STATE0;
    if (value >= BAR_STATE1_TRIGGER && value < BAR_STATE2_TRIGGER)
      return BAR_STATE1;

    return BAR_STATE2;
  };

  render() {
    const { value } = this.state;

    if (!value && value !== 0) return null;

    return (
      <Tooltip
        placement="top"
        title={<FormattedMessage id="ofStorageIsUtilized" values={{ value }} />}
      >
        <StyledProgressBar
          variant="determinate"
          value={value >= MIN_VISIBLE_VALUE ? value : MIN_VISIBLE_VALUE}
        />
      </Tooltip>
    );
  }
}
