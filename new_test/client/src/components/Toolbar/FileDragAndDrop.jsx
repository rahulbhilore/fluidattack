import React, { Component } from "react";
import PropTypes from "prop-types";
import { styled } from "@material-ui/core/styles";
import Typography from "@material-ui/core/Typography";
import { FormattedMessage } from "react-intl";
import UserInfoStore from "../../stores/UserInfoStore";
import FilesListStore from "../../stores/FilesListStore";
import modalStore from "../../stores/ModalStore";

const StyledContainer = styled("div")(({ theme }) => ({
  backgroundColor: "rgba(0, 0, 0, 0.7)",
  border: `dotted 2px ${theme.palette.KYLO}`,
  position: "fixed",
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  zIndex: 9999,
  "& *": {
    pointerEvents: "none"
  }
}));

const StyledTypography = styled(Typography)(() => ({
  color: "#f2f2f2",
  fontWeight: "bold",
  top: "50%",
  left: "50%",
  position: "absolute",
  transform: "translate(-50%, -50%)"
}));

export default class FileDragAndDrop extends Component {
  static propTypes = {
    dropHandler: PropTypes.func.isRequired
  };

  static isDragElementCorrect(event) {
    if (!event.dataTransfer.items.length) return false;

    return event.dataTransfer.types.find(
      item => item.toLowerCase() === `files`
    );
  }

  constructor(props) {
    super(props);
    this.state = {
      isVisible: false
    };
  }

  componentDidMount() {
    document.addEventListener("dragover", this.dragEnter);
    document.addEventListener("dragleave", this.leaveDrag);
  }

  componentWillUnmount() {
    document.removeEventListener("dragover", this.dragEnter);
    document.removeEventListener("dragleave", this.leaveDrag);
  }

  dragEnter = event => {
    if (
      (window.ARESKudoConfigObject.trial !== "true" ||
        UserInfoStore.getUserInfo("isAdmin")) &&
      FileDragAndDrop.isDragElementCorrect(event) &&
      FilesListStore.getCurrentState() !== "trash" &&
      !modalStore.isDialogOpen()
    ) {
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "copy";
      this.setState({
        isVisible: true
      });
    }
  };

  leaveDrag = event => {
    const { isVisible } = this.state;
    if (isVisible && event.target.id === "fileDropContainer") {
      this.setState({
        isVisible: false
      });
    }
  };

  onDrop = event => {
    const { dropHandler } = this.props;

    this.setState({
      isVisible: false
    });

    dropHandler(event);
  };

  render() {
    const { isVisible } = this.state;

    if (!isVisible) return null;

    return (
      <StyledContainer id="fileDropContainer" onDrop={this.onDrop}>
        <StyledTypography>
          <FormattedMessage id="dropHereToUpload" />
        </StyledTypography>
      </StyledContainer>
    );
  }
}
