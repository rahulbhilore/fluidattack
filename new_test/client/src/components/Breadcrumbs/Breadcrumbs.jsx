import React, { Component } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import Breadcrumbs from "@material-ui/core/Breadcrumbs";
import Link from "@material-ui/core/Link";
import withStyles from "@material-ui/core/styles/withStyles";
import clsx from "clsx";
import MainFunctions from "../../libraries/MainFunctions";
import ModalStore from "../../stores/ModalStore";
import FilesListStore from "../../stores/FilesListStore";
import FilesListActions from "../../actions/FilesListActions";
import Logger from "../../utils/Logger";
import SingleBreadcrumb from "./SingleBreadcrumb";
import ApplicationActions from "../../actions/ApplicationActions";
import Separator from "./Separator";
import HomeIcon from "./HomeIcon";

const betweenNumConstant = 5;

const BreadcrumbsContainer = withStyles(theme => ({
  root: {
    marginLeft: 30,
    marginTop: theme.spacing(2),
    marginBottom: theme.spacing(2),
    "&.loading": {
      opacity: 0.3
    },
    "&.search": {
      margin: 0,
      backgroundColor: theme.palette.YELLOW_BREADCRUMB,
      padding: "8px 0 8px 5px"
    },
    "@media (max-width: 767px)": {
      marginTop: theme.spacing(1),
      marginBottom: theme.spacing(1),
      marginLeft: theme.spacing(1)
    }
  },
  separator: {
    margin: 0
  }
}))(Breadcrumbs);

// Breadcrumbs should be either controlled or uncontrolled
// Controlled - pass path
// Uncontrolled - pass targetId

export default class KudoBreadcrumbs extends Component {
  static propTypes = {
    targetId: PropTypes.string,
    storage: PropTypes.shape({
      type: PropTypes.string,
      id: PropTypes.string
    }).isRequired,
    className: PropTypes.string,
    path: PropTypes.arrayOf(
      PropTypes.shape({
        _id: PropTypes.string,
        name: PropTypes.string
      })
    ),
    isTrash: PropTypes.bool,
    onPathChange: PropTypes.func
  };

  static defaultProps = {
    targetId: "",
    path: null,
    isTrash: false,
    className: "",
    onPathChange: path => null
  };

  constructor(props) {
    super(props);
    const isControlled = props.targetId === "";
    this.state = {
      isControlled,
      path: isControlled ? null : [],
      isLoaded: isControlled
    };
  }

  componentDidMount() {
    const { targetId, onPathChange } = this.props;
    const { isControlled } = this.state;
    // for controlled component - no need to do anything
    if (!isControlled) {
      // load path
      this.getFolderPath(targetId)
        .then(newState => {
          Logger.addEntry("BREADCRUMBS", "getFolderPath - success", newState);
          if (onPathChange) onPathChange(newState.path);
        })
        .catch(defaultPath => {
          Logger.addEntry(
            "ERROR",
            "BREADCRUMBS",
            "getFolderPath - error",
            defaultPath
          );
        });
    }
  }

  getFolderPath(targetId) {
    // return promise to be used in SearchLoader
    return new Promise((resolve, reject) => {
      FilesListActions.loadPath(targetId, "folder", true)
        .then(path => {
          const newState = {
            path,
            isLoaded: true
          };
          this.setState(newState);
          resolve(newState);
        })
        .catch(err => {
          // disabled due to XENON-17121
          // SnackbarUtils.alertError(answer.error);
          Logger.addEntry(
            "ERROR",
            `Breadcrumbs -> getFolderPath(${targetId}) error: ${err}`
          );
          const newState = {
            path: [{ id: "-1", name: "~", viewOnly: false }],
            isLoaded: true
          };
          this.setState(newState);
          reject(new Error(newState));
        });
    });
  }

  switchFolder = (name, folderId, viewOnly) => {
    if (
      ModalStore.isDialogOpen() &&
      ModalStore.getCurrentDialogType() === "MODAL_CHOOSE_OBJECT"
    ) {
      FilesListActions.changeXrefFolder(name, folderId, viewOnly);
    } else {
      const { storage } = this.props;

      const { type: storageType, id: storageId } = storage;
      const storageCode = MainFunctions.serviceNameToStorageCode(storageType);
      // this will force proper storage
      FilesListStore.prepareOpen(storageType, storageId);

      const { objectId: folder } = MainFunctions.parseObjectId(folderId);

      const { isControlled } = this.state;
      if (isControlled) {
        // update path faster
        FilesListActions.updatePath(folder, name);
      }

      ApplicationActions.changePage(
        `/files/${storageCode}/${storageId}/${folder}`,
        "BREAD_switchFolder"
      );
    }
  };

  switchToRoot = e => {
    e.preventDefault();
    document.activeElement.blur();
    this.switchFolder("~", "-1", false);
  };

  render() {
    const { path, isControlled, isLoaded } = this.state;
    const { targetId, path: propPath, isTrash, className } = this.props;
    const realPath = (isControlled ? propPath : path) || [];
    if (!realPath || realPath.length === 0) return null;
    const last = _.last(realPath);
    const first = _.first(realPath);
    const baseURL = `${window.ARESKudoConfigObject.UIPrefix}files/${
      isTrash ? "trash/" : ""
    }`;

    if (isTrash) {
      return (
        <Breadcrumbs aria-label="breadcrumb">
          <Link href={baseURL + first._id} onClick={this.switchToRoot}>
            <HomeIcon isTrash />
          </Link>
        </Breadcrumbs>
      );
    }
    return (
      <BreadcrumbsContainer
        className={clsx(isLoaded ? "" : "loading", className)}
        separator={<Separator />}
        aria-label="breadcrumb"
        maxItems={betweenNumConstant}
        itemsAfterCollapse={2}
      >
        <Link
          href={baseURL + first._id}
          onClick={this.switchToRoot}
          data-component="breadcrumbs_home"
        >
          <HomeIcon isTrash={false} />
        </Link>
        {realPath.slice(1, realPath.length - 1).map(element => (
          <SingleBreadcrumb
            key={`br_${element._id}`}
            text={MainFunctions.shrinkString(element.name)}
            name={element.name}
            id={element._id}
            href={baseURL + element._id}
            isLast={false}
            switchFolder={this.switchFolder}
          />
        ))}
        {first._id !== last._id ? (
          <SingleBreadcrumb
            isLast={!targetId}
            id={last._id}
            name={last.name}
            href={baseURL + last._id}
            text={MainFunctions.shrinkString(last.name)}
            switchFolder={this.switchFolder}
          />
        ) : null}
      </BreadcrumbsContainer>
    );
  }
}
