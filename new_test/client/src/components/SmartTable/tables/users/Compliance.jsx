import React from "react";
import PropTypes from "prop-types";
import Immutable from "immutable";
import Typography from "@material-ui/core/Typography";
import Tooltip from "@material-ui/core/Tooltip";

export default function Compliance({ compliance }) {
  const getCaptions = () => {
    let status = "status: ";
    let testResult = "testResult: ";
    let visiblePart = "";
    if (compliance) {
      if (compliance.has("status")) {
        status += compliance.get("status");
        if (compliance.get("status").startsWith("OVERRIDDEN")) {
          visiblePart = compliance.get("status");
        } else {
          visiblePart = compliance.get("testResult");
        }
      } else {
        status += "NONE";
        visiblePart = compliance.get("testResult");
      }
      if (compliance.has("testResult")) {
        testResult += compliance.get("testResult");
      } else {
        testResult += "NONE";
      }
    } else {
      status += "NONE";
      testResult += "NONE";
      visiblePart = "NONE";
    }
    return { status, testResult, visiblePart };
  };

  const { status, testResult, visiblePart } = getCaptions();

  return (
    <Tooltip
      placement="top"
      title={
        <>
          <Typography>{status}</Typography>
          <Typography>{testResult}</Typography>
        </>
      }
    >
      <Typography>{visiblePart}</Typography>
    </Tooltip>
  );
}

Compliance.propTypes = {
  compliance: PropTypes.instanceOf(Immutable.Map).isRequired
};
