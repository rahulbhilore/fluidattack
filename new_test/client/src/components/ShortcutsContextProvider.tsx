// DK: this has to be removed once we remove react-shortcuts
// @ts-ignore
import { ShortcutManager } from "react-shortcuts";
import React, { Component } from "react";
import PropTypes from "prop-types";
import keymap from "../configs/shortcuts";

const shortcutManager = new ShortcutManager(keymap);

type Props = {
  children: React.ReactNode | Array<React.ReactNode>;
};

export default class ShortcutContextProvider extends Component<Props, never> {
  static propTypes = {
    children: PropTypes.oneOfType([
      PropTypes.arrayOf(PropTypes.node),
      PropTypes.node
    ]).isRequired
  };

  static childContextTypes = {
    shortcuts: PropTypes.instanceOf(ShortcutManager).isRequired
  };

  getChildContext() {
    return { shortcuts: shortcutManager };
  }

  render() {
    const { children } = this.props;
    // eslint-disable-next-line react/jsx-no-useless-fragment
    return <>{children}</>;
  }
}
