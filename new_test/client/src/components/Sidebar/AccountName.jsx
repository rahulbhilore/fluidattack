import React, { Component } from "react";
import PropTypes from "prop-types";
import { styled } from "@material-ui/core";

const ACCOUNT_NAME_PADDING = 20;

let sideBarWidth = null;

const StyledSpan = styled("span")(({ theme }) => {
  if (!sideBarWidth)
    sideBarWidth = +theme.kudoStyles.SIDEBAR_WIDTH.replace(/px/, "");

  return {
    whiteSpace: "nowrap"
  };
});

const WebDavName = styled("span")(() => ({
  display: "inline-block",
  whiteSpace: "nowrap",
  lineHeight: "17px",
  fontSize: 12
}));

export default class AccountName extends Component {
  static propTypes = {
    accountName: PropTypes.string.isRequired,
    showTooltip: PropTypes.func.isRequired,
    isWebDav: PropTypes.bool,
    displayName: PropTypes.string,
    storageName: PropTypes.string.isRequired,
    parentWidth: PropTypes.number.isRequired
  };

  static defaultProps = {
    isWebDav: false,
    displayName: null
  };

  constructor(props) {
    super(props);
    this.text = React.createRef();
    this.email = React.createRef();
    this.server = React.createRef();
    this.state = {
      name: "",
      server: "",
      email: "",
      isWidthCalculated: false
    };
  }

  componentDidMount() {
    this.setState((state, props) => {
      const { accountName: name, isWebDav, displayName } = props;
      if (!isWebDav)
        return {
          name
        };

      const [email, server] = name.split(" at ");

      return {
        email: displayName || email,
        server
      };
    }, this.calculateWidth);
  }

  componentDidUpdate(prevProps) {
    const { accountName, parentWidth, displayName, isWebDav } = this.props;
    const {
      accountName: prevAccountName,
      parentWidth: prevParentWidth,
      displayName: prevDisplayName
    } = prevProps;

    const { isWidthCalculated } = this.state;

    if (!isWidthCalculated) this.calculateWidth();

    if (parentWidth !== prevParentWidth) this.calculateWidth();

    if (accountName !== prevAccountName || displayName !== prevDisplayName)
      this.updateAccountName(isWebDav);
  }

  updateAccountName = isWebDav => {
    if (isWebDav) {
      this.setState((state, props) => {
        const [email, server] = props.accountName.split(" at ");

        return {
          email: props.displayName || email,
          server
        };
      });
    } else {
      this.setState((state, props) => ({
        name: props.accountName,
        isWidthCalculated: false
      }));
    }
  };

  calculateWidth = () => {
    if (!sideBarWidth) return;

    this.setState((state, props) => {
      const { showTooltip, isWebDav, displayName } = props;

      if (!isWebDav)
        return {
          name: this.calculate(this.text, props.accountName),
          isWidthCalculated: true
        };

      showTooltip(true);

      const [email, server] = props.accountName.split(" at ");

      return {
        email: this.calculate(this.email, displayName || email, false),
        server: this.calculate(this.server, server, false),
        isWidthCalculated: true
      };
    });
  };

  calculate = (ref, mustBeShortened, needHandleTooltip = true) => {
    const { showTooltip, parentWidth } = this.props;
    const widthRatio =
      ref.current.offsetWidth / (parentWidth - ACCOUNT_NAME_PADDING);

    let shortenedName = mustBeShortened;
    const newNumberOfChars = Math.floor(shortenedName.length / widthRatio);

    if (newNumberOfChars < shortenedName.length) {
      if (needHandleTooltip) showTooltip(true);
      shortenedName = `${shortenedName.substr(0, newNumberOfChars - 2)}...`;
    }

    return shortenedName;
  };

  render() {
    const { isWebDav, storageName } = this.props;
    const { name, email, server } = this.state;

    if (!isWebDav)
      return (
        <StyledSpan
          ref={this.text}
          data-component={`storage-text-${storageName}`}
        >
          {name}
        </StyledSpan>
      );

    return (
      <>
        <WebDavName
          ref={this.email}
          data-component={`storage-text-${storageName}`}
        >
          {email}
        </WebDavName>
        <WebDavName
          ref={this.server}
          data-component={`storage-text-${storageName}`}
        >
          {server}
        </WebDavName>
      </>
    );
  }
}
