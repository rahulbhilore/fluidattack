import React from "react";
import _ from "underscore";
import { Link } from "react-router";
import propTypes from "prop-types";
import clsx from "clsx";
import styled from "@material-ui/core/styles/styled";
import Typography from "@material-ui/core/Typography";
import MainFunctions from "../../../../libraries/MainFunctions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import TableEditField from "../../TableEditField";
import Thumbnail from "../../../Thumbnail";
import TableStore from "../../../../stores/TableStore";
import FilesListStore from "../../../../stores/FilesListStore";
import applicationStore from "../../../../stores/ApplicationStore";
import iconsDictionary from "../../../../constants/appConstants/ObjectIcons";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import IconBlock from "./innerComponents/IconBlock";
import NameBlock from "./innerComponents/NameBlock";
import Highlight from "./innerComponents/Highlight";

const StyledLinkText = styled(Typography)(({ theme }) => ({
  color: theme.palette.VADER,
  cursor: "pointer",
  display: "inline-block",
  transitionDuration: "0.12s",
  verticalAlign: "top",
  lineHeight: "80px",
  "&.xref": {
    lineHeight: "38px",
    "&.isSelected": {
      color: theme.palette.LIGHT
    }
  }
}));

const NAME_PADDING = -20;

export default class Name extends React.Component {
  static propTypes = {
    tableId: propTypes.string.isRequired,
    name: propTypes.string.isRequired,
    id: propTypes.string.isRequired,
    shared: propTypes.bool,
    icon: propTypes.string,
    thumbnail: propTypes.string,
    type: propTypes.oneOf(["file", "folder", "deleted"]).isRequired,
    mimeType: propTypes.string,
    processes: propTypes.objectOf(
      propTypes.shape({
        id: propTypes.string,
        name: propTypes.string,
        state: propTypes.string,
        value: propTypes.string
      })
    ),
    nonBlockingProcess: propTypes.bool,
    mode: propTypes.oneOf(["view", "edit"]),
    storage: propTypes.string,
    accountId: propTypes.string
  };

  static defaultProps = {
    shared: false,
    icon: "",
    thumbnail: "",
    mimeType: "",
    nonBlockingProcess: true,
    mode: "view",
    storage: "",
    accountId: "",
    processes: {}
  };

  static getExtension(type, name) {
    return type === "folder"
      ? "folder"
      : MainFunctions.getExtensionFromName(name);
  }

  constructor(props) {
    super(props);
    const openFlag = this.getOpenFlag();
    this.state = {
      openFlag,
      renderIcon: null,
      openLink: null,
      isGenericIcon: false,
      isTooltipToShow: false,
      shortenedName: ""
    };
  }

  componentDidMount() {
    this.getRenderIcon();
    this.setState({ openLink: this.getOpenLink() });
    this.checkName();
  }

  componentDidUpdate(prevProps) {
    const { name, id, shared, thumbnail } = this.props;
    if (
      prevProps.name !== name ||
      prevProps.id !== id ||
      prevProps.shared !== shared ||
      prevProps.thumbnail !== thumbnail
    ) {
      // eslint-disable-next-line react/no-did-update-set-state
      this.setState(
        {
          openFlag: this.getOpenFlag()
        },
        () => {
          this.getRenderIcon();
          this.setState({ openLink: this.getOpenLink() });
        }
      );
    }
    if (prevProps.name !== name) {
      this.checkName();
    }
  }

  checkName = () => {
    const { isTooltipToShow } = this.state;
    let newTooltip = true;
    if (this.nameSpan.offsetWidth <= this.widthFixDiv.offsetWidth) {
      newTooltip = false;
    }
    if (newTooltip !== isTooltipToShow) {
      this.setState({
        isTooltipToShow: newTooltip,
        shortenedName: this.calculateShortenedName()
      });
    }
  };

  calculateShortenedName = () => {
    const { name: mustBeShortened } = this.props;
    const widthRatio =
      this.nameSpan.offsetWidth / (this.widthFixDiv.offsetWidth - NAME_PADDING);

    let shortenedName = mustBeShortened;
    const newNumberOfChars = Math.floor(shortenedName.length / widthRatio);

    if (newNumberOfChars < shortenedName.length) {
      shortenedName = `${shortenedName.substr(0, newNumberOfChars - 2)}...`;
    }

    return shortenedName;
  };

  getRenderIcon = () => {
    const { props } = this;
    const ext = Name.getExtension(props.type, props.name);
    if (ext === "folder") {
      let svgLink = iconsDictionary.folderSVG;
      if (props.icon) {
        svgLink = props.icon;
      } else if (props.type === "folder" && props.shared) {
        svgLink = iconsDictionary.folderSharedSVG;
      }

      this.setState({
        renderIcon: (
          <img className="containerTypeImage" src={svgLink} alt={props.name} />
        ),
        isGenericIcon: true
      });
    } else if (
      UserInfoStore.findApp(ext, props.mimeType) === "xenon" &&
      props.thumbnail
    ) {
      this.setState({
        renderIcon: (
          <Thumbnail
            className="containerTypeImage"
            src={props.thumbnail}
            fileId={props.id}
          />
        ),
        isGenericIcon: false
      });
    } else {
      const svgType = UserInfoStore.getIconClassName(
        ext,
        props.type,
        props.name,
        props.mimeType
      );
      // just in case, but we should
      // always be able to get from the dictionary.
      let svgLink = `images/icons/${svgType}.svg`;
      if (
        Object.prototype.hasOwnProperty.call(iconsDictionary, `${svgType}SVG`)
      ) {
        svgLink = iconsDictionary[`${svgType}SVG`];
      }
      this.setState({
        renderIcon: (
          <img className="containerTypeImage" src={svgLink} alt={props.name} />
        ),
        isGenericIcon: true
      });
    }
  };

  getOpenFlag = () => {
    const { type, name, processes, nonBlockingProcess, mimeType } = this.props;
    const ext = Name.getExtension(type, name);
    if (!Object.keys(processes).length || nonBlockingProcess === true) {
      return (
        UserInfoStore.extensionSupported(ext, mimeType) &&
        FilesListStore.getCurrentState() !== "trash"
      );
    }
    return false;
  };

  getOpenLink = () => {
    const { type, name, id, storage, accountId, mimeType } = this.props;
    const { openFlag } = this.state;
    const { storageType, storageId, objectId } =
      MainFunctions.parseObjectId(id);
    if (openFlag === true) {
      if (type === "folder") {
        return `${applicationStore.getApplicationSetting("UIPrefix")}files/${
          storage || storageType
        }/${accountId || storageId}/${objectId}`;
      }
      const app = UserInfoStore.findApp(
        MainFunctions.getExtensionFromName(name),
        mimeType
      );
      if (app === "xenon") {
        return `${applicationStore.getApplicationSetting("UIPrefix")}file/${
          storage || storageType
        }+${accountId || storageId}+${objectId}`;
      }
    }
    return null;
  };

  isLinkClickable = () => {
    const { processes, mode } = this.props;
    const { openFlag } = this.state;
    return openFlag && mode !== "edit" && _.isEmpty(processes);
  };

  onLinkClick = e => {
    const { tableId, type, id } = this.props;
    const { openFlag, openLink } = this.state;
    if (e.button === 0) {
      if (TableStore.getTable(tableId).type === "xref") {
        e.preventDefault();
        if (type === "folder") {
          FilesListStore.openXrefFolder(id);
        }
      } else if (!openLink && openFlag === true) {
        e.preventDefault();
        FilesListStore.open(id);
      } else if (!this.isLinkClickable()) e.preventDefault();
    }
  };

  render() {
    const { mode, name, id, type, tableId } = this.props;
    const {
      isGenericIcon,
      renderIcon,
      openLink,
      isTooltipToShow,
      shortenedName
    } = this.state;
    let highlightString = "";
    if (
      TableStore.getTable(tableId).highlight &&
      Object.prototype.hasOwnProperty.call(
        TableStore.getTable(tableId).highlight,
        "name"
      )
    ) {
      highlightString = TableStore.getTable(tableId).highlight.name || "";
    }

    const nameComponent = (
      <div
        ref={widthFixDiv => {
          this.widthFixDiv = widthFixDiv;
        }}
        data-component="objectName"
        data-text={name}
      >
        <Link to={openLink} onClick={this.onLinkClick}>
          <StyledLinkText
            className={clsx(
              TableStore.getTable(tableId).type === "xref" ? "xref" : "",
              TableStore.isSelected(tableId, id) ? "isSelected" : ""
            )}
            component="span"
            ref={nameSpan => {
              this.nameSpan = nameSpan;
            }}
          >
            {highlightString.length > 0 ? (
              <Highlight string={name} highlightPart={highlightString} />
            ) : (
              shortenedName || name
            )}
          </StyledLinkText>
        </Link>
      </div>
    );

    return (
      <td>
        <IconBlock
          renderIcon={renderIcon}
          isGenericIcon={isGenericIcon}
          type={TableStore.getTable(tableId).type}
          isSelected={TableStore.isSelected(tableId, id)}
        />
        <NameBlock
          accessible={this.isLinkClickable()}
          type={TableStore.getTable(tableId).type}
        >
          {mode === "edit" ? (
            <TableEditField
              fieldName="name"
              value={name}
              id={id}
              type={type}
              extensionEdit={type === "file"}
            />
          ) : null}
          <SmartTooltip
            forcedOpen={isTooltipToShow}
            placement="top"
            title={name}
          >
            {nameComponent}
          </SmartTooltip>
        </NameBlock>
      </td>
    );
  }
}
