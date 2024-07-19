import React from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Tooltip from "@material-ui/core/Tooltip";
import copy from "copy-to-clipboard";
import KudoButton from "../../../../Inputs/KudoButton/KudoButton";
import SnackbarUtils from "../../../../Notifications/Snackbars/SnackController";

function CopyButton({ link, className }) {
  const handleCopy = () => {
    copy(link);
    SnackbarUtils.alertInfo({ id: "copiedToClipboard" });
  };
  return (
    <Tooltip placement="top" title={<FormattedMessage id="copyToClipboard" />}>
      <KudoButton
        className={className}
        isDisabled={false}
        onClick={handleCopy}
        dataComponent="copy-button"
        styles={{
          button: {
            height: "38px"
          }
        }}
      >
        <FormattedMessage id="copy" />
      </KudoButton>
    </Tooltip>
  );
}

CopyButton.propTypes = {
  link: PropTypes.string.isRequired,
  className: PropTypes.string
};

CopyButton.defaultProps = {
  className: ""
};

export default CopyButton;
