import React, { PureComponent } from "react";
import propTypes from "prop-types";
import ModalActions from "../../../actions/ModalActions";
import userInfoStore, {
  INTERCOM_RELAUNCHED
} from "../../../stores/UserInfoStore";

export default class PermissionsPage extends PureComponent {
  static propTypes = {
    params: propTypes.shape({
      id: propTypes.string.isRequired
    }).isRequired
  };

  static hideIntercomLauncher() {
    if (window.Intercom) {
      window.Intercom("update", {
        hide_default_launcher: true
      });
    }
  }

  componentDidMount() {
    const { params } = this.props;
    userInfoStore.addListener(
      INTERCOM_RELAUNCHED,
      PermissionsPage.hideIntercomLauncher
    );
    setTimeout(() => {
      ModalActions.shareManagement(params.id);
      PermissionsPage.hideIntercomLauncher();
    }, 0);
  }

  componentWillUnmount() {
    userInfoStore.removeListener(
      INTERCOM_RELAUNCHED,
      PermissionsPage.hideIntercomLauncher
    );
  }

  render() {
    return <div />;
  }
}
