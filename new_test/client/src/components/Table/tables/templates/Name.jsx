import React from "react";
import PropTypes from "prop-types";
import TableEditField from "../../TableEditField";
import SmartTooltip from "../../../SmartTooltip/SmartTooltip";
import drawingSVG from "../../../../assets/images/icons/drawing.svg";
import UploadProgress from "../files/innerComponents/UploadProgress";

export default class Name extends React.Component {
  static propTypes = {
    name: PropTypes.string.isRequired,
    id: PropTypes.string.isRequired,
    type: PropTypes.string.isRequired,
    mode: PropTypes.string,
    processes: PropTypes.shape({
      [PropTypes.string]: PropTypes.shape({
        name: PropTypes.string,
        type: PropTypes.string,
        id: PropTypes.string
      })
    })
  };

  static defaultProps = {
    mode: "view",
    processes: {}
  };

  constructor(props) {
    super(props);
    this.state = {
      isTooltipToShow: false
    };
    this.nameSpan = React.createRef();
    this.widthFixDiv = React.createRef();
  }

  componentDidUpdate(prevProps) {
    const { name, mode } = this.props;
    if (name !== prevProps.name && mode !== "edit") {
      this.checkTooltip();
    }
  }

  checkTooltip = () => {
    const { isTooltipToShow } = this.state;
    let newTooltip = true;
    if (
      this.nameSpan.current.offsetWidth <= this.widthFixDiv.current.offsetWidth
    ) {
      newTooltip = false;
    }
    if (isTooltipToShow !== newTooltip) {
      this.setState({
        isTooltipToShow: newTooltip
      });
    }
  };

  // eslint-disable-next-line react/no-unused-class-component-methods
  edit() {
    const { name, id, type } = this.props;

    return (
      <td>
        <div className="typeImageOverlay genericIcon regularRow">
          <img className="containerTypeImage" src={drawingSVG} alt={name} />
        </div>
        <div className="overlayWithProgressBar">
          <TableEditField
            fieldName="name"
            value={name}
            id={id}
            type={type}
            extensionEdit
          />
        </div>
      </td>
    );
  }

  // eslint-disable-next-line react/no-unused-class-component-methods
  view() {
    const { name, processes } = this.props;
    let singleProcess = null;
    if (Object.keys(processes).length > 0) {
      // should we use multiprocessing as well?
      [singleProcess] = Object.values(processes);
    }
    const { isTooltipToShow } = this.state;
    return (
      <td>
        <div className="typeImageOverlay genericIcon regularRow">
          <img className="containerTypeImage" src={drawingSVG} alt={name} />
        </div>
        <div className="overlayWithProgressBar">
          <SmartTooltip
            forcedOpen={isTooltipToShow}
            placement="top"
            title={name}
          >
            <div ref={this.widthFixDiv}>
              <span ref={this.nameSpan} className="objectname regularRow">
                {name}
              </span>
            </div>
          </SmartTooltip>
        </div>
        {singleProcess !== null ? (
          <UploadProgress
            customClass="templates"
            renderColumn={false}
            showLabel
            name={singleProcess.name}
            value={singleProcess.value}
            id={singleProcess.id || singleProcess.name}
          />
        ) : null}
      </td>
    );
  }

  render() {
    const { mode } = this.props;
    return this[mode]();
  }
}
