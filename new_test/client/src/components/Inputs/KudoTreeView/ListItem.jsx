import React, { Component } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import { styled } from "@material-ui/core";
import { SortDirection } from "react-virtualized";
import { sortByName } from "../../../utils/FileSort";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore, { CONTENT_LOADED } from "../../../stores/FilesListStore";
import ItemLabel from "./ItemLabel";

const StyledLi = styled("li")(() => ({
  listStyleType: "none",
  "&.notMove": {
    "&, & > *": {
      cursor: "not-allowed"
    }
  }
}));

const StyledOl = styled("ol")(() => ({
  paddingInlineStart: "15px"
}));

export default class ListItem extends Component {
  static propTypes = {
    unfolded: PropTypes.bool.isRequired,
    objectType: PropTypes.string.isRequired,
    objectId: PropTypes.string.isRequired,
    objectName: PropTypes.string.isRequired,
    icon: PropTypes.string,
    mimeType: PropTypes.string,
    parent: PropTypes.string,
    handleChange: PropTypes.func,
    allowedTypes: PropTypes.arrayOf(PropTypes.string).isRequired,
    offsetLevel: PropTypes.number,
    selectedFolder: PropTypes.string,
    allowedExtensions: PropTypes.string,
    allFoldersAllowed: PropTypes.bool,
    validateValue: PropTypes.func,
    objectInfo: PropTypes.shape({ _id: PropTypes.string.isRequired }),
    storageType: PropTypes.string.isRequired,
    storageId: PropTypes.string.isRequired,
    customGetContent: PropTypes.func,
    customFoldCheck: PropTypes.func,
    disableSubfoldersOfSelected: PropTypes.bool,
    selectedObjects: PropTypes.arrayOf(PropTypes.string)
  };

  static defaultProps = {
    handleChange: () => null,
    parent: null,
    icon: "",
    mimeType: "",
    offsetLevel: 0,
    selectedFolder: null,
    allowedExtensions: null,
    allFoldersAllowed: false,
    validateValue: () => true,
    objectInfo: {},
    customGetContent: null,
    customFoldCheck: null,
    disableSubfoldersOfSelected: false,
    selectedObjects: []
  };

  // linear represent of tree for interacting by keypress
  static linearTree = [];

  static onKeyPress(action) {
    let noSelectedElements = true;
    let selectedIndex = null;

    switch (action) {
      case "MOVE_UP":
        ListItem.linearTree.forEach((elem, index) => {
          const { object } = elem;
          if (object.props.selectedFolder === object.props.objectId) {
            noSelectedElements = false;
            selectedIndex = index;

            if (index === 0) return;

            setTimeout(() => {
              const { object: nextObject } = ListItem.linearTree[index - 1];
              nextObject.handleMoveKey();
            }, 0);
          }
        });
        break;
      case "MOVE_DOWN":
        ListItem.linearTree.forEach((elem, index) => {
          const { object } = elem;
          if (object.props.selectedFolder === object.props.objectId) {
            noSelectedElements = false;
            selectedIndex = index;

            if (index === ListItem.linearTree.length - 1) return;

            setTimeout(() => {
              const { object: nextObject } = ListItem.linearTree[index + 1];
              nextObject.handleMoveKey();
            }, 0);
          }
        });
        break;
      case "ENTER_FOLDER":
        ListItem.linearTree.forEach((elem, index) => {
          const { object } = elem;
          if (object.props.selectedFolder === object.props.objectId) {
            noSelectedElements = false;
            selectedIndex = index;
            setTimeout(() => {
              object.handleInKey();
            }, 0);
          }
        });
        break;
      case "BACK_FOLDER":
        ListItem.linearTree.forEach((elem, index) => {
          const { object } = elem;
          if (object.props.selectedFolder === object.props.objectId) {
            noSelectedElements = false;
            selectedIndex = index;
            setTimeout(() => {
              object.handleOutKey();
            }, 0);
          }
        });
        break;
      default:
        break;
    }

    if (!noSelectedElements) return selectedIndex;

    ListItem.linearTree[0].object.handleMoveKey();

    return selectedIndex;
  }

  constructor(props) {
    super(props);
    const isCustomFoldCheckProvided =
      Object.prototype.hasOwnProperty.call(props, "customFoldCheck") &&
      props.customFoldCheck !== null;
    const { disableSubfoldersOfSelected, selectedObjects } = props;

    let isChecked = false;
    let isInside = false;
    if (
      (!disableSubfoldersOfSelected ||
        !selectedObjects.includes(props.objectId)) &&
      isCustomFoldCheckProvided
    ) {
      isInside = props.customFoldCheck(props.objectId, props.objectInfo);
      isChecked = true;
    } else if (
      disableSubfoldersOfSelected &&
      selectedObjects.includes(props.objectId)
    ) {
      // it should be considered checked so that no arrow is shown
      isChecked = true;
    }
    this.state = {
      unfolded: props.unfolded,
      folders: [],
      files: [],
      isChecked,
      inside: isInside,
      isLoading: false
    };
  }

  componentDidMount() {
    const { objectId, parent, objectName, unfolded } = this.props;
    if (unfolded) {
      this.getFolderContent();
      FilesListStore.addEventListener(CONTENT_LOADED, this.onContentLoaded);
    }

    if (!parent)
      ListItem.linearTree.push({
        object: this,
        id: objectId,
        parent
      });
    else {
      let pasteIndex = ListItem.linearTree.findIndex(
        elem => elem.parent === parent
      );

      if (pasteIndex === -1) {
        pasteIndex = ListItem.linearTree.findIndex(elem => elem.id === parent);
        ListItem.linearTree.splice(pasteIndex + 1, 0, {
          object: this,
          id: objectId,
          parent,
          objectName
        });
      } else {
        pasteIndex = _.findLastIndex(
          ListItem.linearTree,
          elem => elem.parent === parent
        );
        ListItem.linearTree.splice(pasteIndex + 1, 0, {
          object: this,
          id: objectId,
          parent,
          objectName
        });
      }
    }
  }

  componentWillUnmount() {
    const { objectId } = this.props;

    FilesListStore.removeEventListener(CONTENT_LOADED, this.onContentLoaded);

    ListItem.linearTree.splice(
      ListItem.linearTree.findIndex(elem => elem.id === objectId),
      1
    );
  }

  getFolderContent = () => {
    const {
      objectId,
      allowedExtensions,
      storageType,
      storageId,
      customGetContent,
      customFoldCheck,
      disableSubfoldersOfSelected,
      selectedObjects
    } = this.props;

    if (!disableSubfoldersOfSelected || !selectedObjects.includes(objectId)) {
      const { objectId: pureId } = MainFunctions.parseObjectId(objectId);
      this.setState({ isLoading: true });
      if (customGetContent) {
        customGetContent(pureId).then(data => {
          this.setState({
            isLoading: false,
            folders: data.folders || [],
            files: data.files || [],
            isChecked: true,
            inside: customFoldCheck ? customFoldCheck(pureId, data) : false
          });
        });
      } else {
        FilesListActions.getFolderContent(
          storageType,
          storageId,
          pureId,
          allowedExtensions,
          { isIsolated: true, recursive: true }
        );
      }
    }
  };

  onContentLoaded = folderId => {
    const {
      objectId,
      allowedTypes,
      allowedExtensions,
      allFoldersAllowed,
      storageType,
      storageId,
      disableSubfoldersOfSelected,
      selectedObjects
    } = this.props;
    const { objectId: pureId } = MainFunctions.parseObjectId(objectId);
    const completeId = `${storageType}+${storageId}+${pureId}`;
    if (
      (!disableSubfoldersOfSelected || !selectedObjects.includes(objectId)) &&
      folderId === completeId
    ) {
      const content = FilesListStore.getTreeData(folderId, allowedExtensions);
      const { file: files = [], folder: folders = [] } = _.groupBy(
        content,
        "type"
      );
      folders.sort((a, b) => sortByName(a, b, SortDirection.ASC));
      files.sort((a, b) => sortByName(a, b, SortDirection.ASC));
      this.setState({
        isLoading: false,
        isChecked: true,
        folders,
        files,
        inside:
          allFoldersAllowed ||
          folders.length !== 0 ||
          (allowedTypes.includes("files") === true && files.length !== 0)
      });
    }
  };

  toggleFolded = e => {
    e.preventDefault();
    e.stopPropagation();
    const {
      handleChange,
      objectId,
      objectInfo,
      disableSubfoldersOfSelected,
      selectedObjects
    } = this.props;
    const { isChecked, unfolded } = this.state;
    handleChange({ value: objectId, isChecked }, objectInfo);
    if (!disableSubfoldersOfSelected || !selectedObjects.includes(objectId)) {
      this.setState({ unfolded: !unfolded });
      if (!isChecked) {
        this.getFolderContent();
        FilesListStore.addEventListener(CONTENT_LOADED, this.onContentLoaded);
      }
    }
  };

  // eslint-disable-next-line react/no-unused-class-component-methods
  handleMoveKey = () => {
    const { handleChange, objectId, objectInfo } = this.props;
    const { isChecked } = this.state;
    handleChange({ value: objectId, isChecked }, objectInfo);
  };

  // eslint-disable-next-line react/no-unused-class-component-methods
  handleInKey = () => {
    const {
      handleChange,
      objectId,
      objectInfo,
      disableSubfoldersOfSelected,
      selectedObjects
    } = this.props;
    const { isChecked } = this.state;

    handleChange({ value: objectId, isChecked }, objectInfo);

    if (!disableSubfoldersOfSelected || !selectedObjects.includes(objectId)) {
      if (!isChecked) {
        this.getFolderContent();
        FilesListStore.addEventListener(CONTENT_LOADED, this.onContentLoaded);
      }

      this.setState({ unfolded: true });
    }
  };

  // eslint-disable-next-line react/no-unused-class-component-methods
  handleOutKey = () => {
    const { handleChange, objectId, objectInfo } = this.props;
    const { isChecked } = this.state;

    if (!isChecked) return;

    handleChange({ value: objectId, isChecked }, objectInfo);
    this.setState({ unfolded: false });
  };

  render() {
    const {
      validateValue,
      objectType,
      objectName,
      objectId,
      allowedTypes,
      offsetLevel,
      selectedFolder,
      handleChange,
      icon,
      allowedExtensions,
      allFoldersAllowed,
      objectInfo,
      mimeType,
      storageType,
      storageId,
      selectedObjects,
      disableSubfoldersOfSelected
    } = this.props;
    const { inside, unfolded, files, folders, isLoading, isChecked } =
      this.state;
    const isAllowedValue = validateValue(objectId, null, objectInfo);
    return (
      <StyledLi className={isAllowedValue ? "" : "notMove"}>
        <ItemLabel
          objectId={objectId}
          objectName={objectName}
          objectType={objectType}
          toggleFolded={this.toggleFolded}
          isInside={inside}
          isLoading={isLoading}
          icon={icon}
          isUnfolded={unfolded}
          isSelected={selectedFolder === objectId}
          isAbleToMove={isAllowedValue}
          isChecked={isChecked}
          mimeType={mimeType}
        />
        {unfolded ? (
          <StyledOl>
            {folders.map(elem => (
              <ListItem
                key={elem.id || elem._id}
                objectId={elem.id || elem._id}
                objectName={elem.name}
                parent={objectId}
                objectType="folder"
                icon={elem.icon || null}
                mimeType={null}
                offsetLevel={offsetLevel + 1}
                shared={elem.shared && !elem.isOwner}
                unfolded={false}
                selectedFolder={selectedFolder}
                handleChange={handleChange}
                allowedTypes={allowedTypes}
                allowedExtensions={allowedExtensions}
                allFoldersAllowed={allFoldersAllowed}
                validateValue={validateValue}
                objectInfo={elem}
                storageType={storageType}
                storageId={storageId}
                selectedObjects={selectedObjects}
                disableSubfoldersOfSelected={disableSubfoldersOfSelected}
              />
            ))}
            {allowedTypes.indexOf("files") > -1
              ? files.map(elem => (
                  <ListItem
                    key={elem._id}
                    objectId={elem._id}
                    objectName={elem.filename}
                    objectType="file"
                    icon={elem.thumbnail}
                    mimeType={elem.mimeType}
                    offsetLevel={offsetLevel + 1}
                    shared={elem.shared && !elem.isOwner}
                    unfolded={false}
                    selectedFolder={selectedFolder}
                    handleChange={handleChange}
                    allowedTypes={allowedTypes}
                    allowedExtensions={allowedExtensions}
                    validateValue={validateValue}
                    objectInfo={elem}
                    selectedObjects={selectedObjects}
                    disableSubfoldersOfSelected={disableSubfoldersOfSelected}
                  />
                ))
              : null}
          </StyledOl>
        ) : null}
      </StyledLi>
    );
  }
}
