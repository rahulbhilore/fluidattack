import React, { Component } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import TableStore from "../../../../stores/TableStore";
import RelativeTime from "../../../RelativeTime/RelativeTime";
import UserInfoStore from "../../../../stores/UserInfoStore";
import TableActions from "../../../../actions/TableActions";
import UploadProgress from "./innerComponents/UploadProgress";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";

export default class Modified extends Component {
  static propTypes = {
    processes: PropTypes.shape({
      [PropTypes.string]: PropTypes.shape({
        name: PropTypes.string,
        type: PropTypes.string,
        id: PropTypes.string
      })
    }),
    id: PropTypes.string.isRequired,
    type: PropTypes.string.isRequired,
    changer: PropTypes.string,
    updateDate: PropTypes.number,
    creationDate: PropTypes.number
  };

  static defaultProps = {
    processes: {},
    changer: "",
    updateDate: 0,
    creationDate: 0
  };

  cancelUploadingProcess = () => {
    const { id, type, processes } = this.props;
    const { id: processId } = Object.values(processes)[0];
    if (processId) {
      TableActions.endProcess(id, "upload", processId);
      setTimeout(() => {
        const cancelID = `CANCEL_${processId}`;
        TableActions.startProcess(id, "upload_canceled", cancelID);
        setTimeout(() => {
          TableActions.endProcess(id, "upload_canceled", cancelID);
          if (type === "folder") {
            document.dispatchEvent(
              new CustomEvent("STOP_UPLOAD", { detail: id })
            );
          } else {
            TableActions.deleteEntity(TableStore.getFocusedTable(), id);
          }
          SnackbarUtils.alertInfo({ id: "uploadCanceled" });
        }, 2000);
      }, 0);
    }
  };

  getChangerName = () => {
    const { changer = "", type } = this.props;
    if (
      (type === "file" && changer === UserInfoStore.getUserInfo("username")) ||
      changer ===
        `${UserInfoStore.getUserInfo("name")} ${UserInfoStore.getUserInfo(
          "surname"
        )}`
    ) {
      return <FormattedMessage id="me" />;
    }
    return changer;
  };

  render() {
    const { changer = "", updateDate, creationDate, processes } = this.props;
    if (Object.keys(processes).length > 0) {
      // should we use multiprocessing as well?
      const singleProcess = Object.values(processes)[0];
      return (
        <UploadProgress
          value={singleProcess.value}
          name={singleProcess.name}
          id={singleProcess.id}
          cancelFunction={this.cancelUploadingProcess}
        />
      );
    }
    let dateStyle = { display: "block" };
    if ((changer || "").length > 0) {
      dateStyle = { display: "inline-block" };
    }

    return (
      <td className="modified">
        <div className="modifyDate" style={dateStyle}>
          {(updateDate || creationDate || 0) !== 0 ? (
            <RelativeTime timestamp={updateDate || creationDate || 0} />
          ) : (
            <span>{String.fromCharCode(8212)}</span>
          )}
        </div>
        <div className="modifierInfo">{this.getChangerName()}</div>
      </td>
    );
  }
}
