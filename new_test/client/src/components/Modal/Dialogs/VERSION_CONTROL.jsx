import React, { Component } from "react";
import PropTypes from "prop-types";
import Immutable from "immutable";
import { FormattedMessage } from "react-intl";
import { Grid, styled } from "@material-ui/core";
import Filters from "./VersionControl/Filters";
import VersionsTable from "./VersionControl/VersionsTable";
import VersionInfo from "./VersionControl/VersionInfo";
import ControlButtons from "./VersionControl/ControlButtons";
import VersionControlActions from "../../../actions/VersionControlActions";
import VersionControlStore from "../../../stores/VersionControlStore";
import * as VersionControlConstants from "../../../constants/VersionControlConstants";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import SmartTableStore, { SELECT_EVENT } from "../../../stores/SmartTableStore";
import SmartTableActions from "../../../actions/SmartTableActions";
import Loader from "../../Loader";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import DragNDrop from "./VersionControl/DragNDrop";
import ModalActions from "../../../actions/ModalActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import MainFunctions from "../../../libraries/MainFunctions";

const StyledTableGrid = styled(Grid)(({ theme }) => ({
  paddingRight: "14px!important",
  [theme.breakpoints.up("sm")]: {
    flexBasis: "74%"
  }
}));

let tableId = null;

export default class VersionControlDialog extends Component {
  static propTypes = {
    changeDialogCaption: PropTypes.func.isRequired,
    info: PropTypes.shape({
      fileId: PropTypes.string.isRequired,
      fileName: PropTypes.string.isRequired,
      folderId: PropTypes.string.isRequired
    }).isRequired
  };

  static handleGetVersionsListError = () => {
    SnackbarUtils.alertError(VersionControlStore.getLastError());
    ModalActions.hide();
  };

  constructor(props) {
    super(props);
    this.state = {
      versions: [],
      filteredVersions: [],
      updated: false,
      dateFilter: null,
      selectedVersions: []
    };
  }

  componentDidMount() {
    const { info, changeDialogCaption } = this.props;
    const { fileId, fileName } = info;

    VersionControlStore.addChangeListener(
      VersionControlConstants.GET_LIST_SUCCESS,
      this.handleVersionsUpdate
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.REMOVE_SUCCESS,
      this.handleUpdate
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.PROMOTE_SUCCESS,
      this.handleUpdate
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.UPLOAD,
      this.handleVersionsUpdate
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.UPLOAD_SUCCESS,
      this.handleUpdate
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.GET_LIST_FAIL,
      VersionControlDialog.handleGetVersionsListError
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.REMOVE_FAIL,
      this.handleRequestError
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.PROMOTE_FAIL,
      this.handleRequestError
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.UPLOAD_FAIL,
      this.handleRequestError
    );

    VersionControlActions.getVersions(fileId);

    changeDialogCaption(
      <FormattedMessage
        id="manageVersionsFor"
        values={{ strong: IntlTagValues.strong, file: fileName }}
      />
    );
  }

  componentWillUnmount() {
    VersionControlStore.removeChangeListener(
      VersionControlConstants.GET_LIST_SUCCESS,
      this.handleVersionsUpdate
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.REMOVE_SUCCESS,
      this.handleUpdate
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.PROMOTE_SUCCESS,
      this.handleUpdate
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.UPLOAD,
      this.handleVersionsUpdate
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.UPLOAD_SUCCESS,
      this.handleUpdate
    );

    SmartTableStore.removeListener(SELECT_EVENT + tableId, this.onTableClick);

    VersionControlStore.removeChangeListener(
      VersionControlConstants.GET_LIST_FAIL,
      VersionControlDialog.handleGetVersionsListError
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.REMOVE_FAIL,
      this.handleRequestError
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.PROMOTE_FAIL,
      this.handleRequestError
    );
    VersionControlStore.removeChangeListener(
      VersionControlConstants.UPLOAD_FAIL,
      this.handleRequestError
    );
  }

  onFilter = (startTime = null, endTime = null) => {
    const { versions } = this.state;

    this.setState({
      filteredVersions: this.applyDateFilter(versions, startTime, endTime),
      dateFilter: startTime
    });
  };

  applyDateFilter = (versions, startTime, endTime) => {
    const prepareVersions = filteredVersions => {
      const { info } = this.props;
      const { fileId, fileName } = info;
      return filteredVersions.map(elem => ({
        _id: elem.id,
        filenameInfo: {
          thumbnail: elem.thumbnail,
          filename: elem.customName,
          realName: fileName
        },
        member: elem?.modifier?.name ? elem?.modifier?.name : "",
        fileId,
        ...elem
      }));
    };

    if (!startTime) return Immutable.fromJS(prepareVersions(versions));

    if (!endTime)
      return Immutable.fromJS(
        prepareVersions(
          versions.filter(version => version.creationTime > startTime)
        )
      );

    return Immutable.fromJS(
      prepareVersions(
        versions.filter(
          version =>
            version.creationTime > startTime && version.creationTime < endTime
        )
      )
    );
  };

  handleVersionsUpdate = () => {
    const versions = VersionControlStore.getVersionsList();
    const { dateFilter } = this.state;

    this.setState({
      updated: true,
      versions,
      filteredVersions: this.applyDateFilter(versions, dateFilter)
    });
  };

  handleUpdate = () => {
    if (MainFunctions.detectPageType() === "file") {
      ModalActions.hide();
    } else {
      const { info } = this.props;
      const { fileId } = info;
      this.setState({
        updated: false
      });
      VersionControlActions.getVersions(fileId);
    }
  };

  handleRequestError = () => {
    this.setState({
      updated: true
    });
    SnackbarUtils.alertError(VersionControlStore.getLastError());
    this.handleUpdate();
  };

  onTableLoaded = smartTableId => {
    tableId = smartTableId;
    const { filteredVersions } = this.state;
    const iter = filteredVersions.get(0);

    if (!iter) return;

    const id = iter.get("id");

    SmartTableStore.addListener(SELECT_EVENT + smartTableId, this.onTableClick);
    setTimeout(() => {
      SmartTableActions.selectRows(tableId, [id]);
    }, 0);
  };

  onTableClick = () => {
    this.setState({
      selectedVersions: [...new Set(SmartTableStore.getSelectedRows(tableId))]
    });
  };

  onButtonAction = () => {
    this.setState({
      updated: false
    });
  };

  render() {
    const { info } = this.props;
    const { fileId, fileName } = info;
    const { updated, filteredVersions, selectedVersions } = this.state;

    if (!updated) {
      return (
        <DialogBody>
          <Loader isModal />
        </DialogBody>
      );
    }

    return (
      <>
        <DialogBody>
          <DragNDrop fileId={fileId} />
          <Grid container spacing={1}>
            <StyledTableGrid item sm={9}>
              <Grid item>
                <Filters onFilter={this.onFilter} />
              </Grid>
              <Grid item>
                <VersionsTable
                  versions={filteredVersions}
                  onTableLoaded={this.onTableLoaded}
                />
              </Grid>
            </StyledTableGrid>
            <Grid item sm={3}>
              <VersionInfo
                fileId={fileId}
                selectedVersions={selectedVersions}
                versions={filteredVersions}
              />
            </Grid>
          </Grid>
        </DialogBody>
        <DialogFooter showCancel={false}>
          <ControlButtons
            selectedVersions={selectedVersions}
            fileId={fileId}
            folderId={info.folderId}
            onButtonAction={this.onButtonAction}
            fileName={fileName}
          />
        </DialogFooter>
      </>
    );
  }
}
