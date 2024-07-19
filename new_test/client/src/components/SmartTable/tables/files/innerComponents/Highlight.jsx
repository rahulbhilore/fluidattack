import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import { makeStyles } from "@material-ui/core/styles";

const useStyles = makeStyles(() => ({
  regular: {},
  highlight: {
    backgroundColor: "rgba(231, 211, 0, 0.5)"
  }
}));

// @link https://stackoverflow.com/questions/3446170/escape-string-for-use-in-javascript-regex
function escapeRegExp(string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export default function Highlight({ string, highlightPart }) {
  const classes = useStyles();
  const [parts, setParts] = useState([]);
  useEffect(() => {
    let regex = /./;
    try {
      regex = new RegExp(highlightPart, "ig");
    } catch (ex) {
      regex = new RegExp(escapeRegExp(highlightPart), "ig");
    }
    let res = [];
    const finalName = [];
    let lastIndex = 0;
    let i = 0; // counter to stop infinite checks
    const maxPartsCount = 10; // maximum number for highlight
    // eslint-disable-next-line no-cond-assign
    while (i < maxPartsCount && (res = regex.exec(string)) !== null) {
      const regularPart = string.substr(lastIndex, res.index - lastIndex) || "";
      if (regularPart.length > 0) {
        finalName.push(<span className={classes.regular}>{regularPart}</span>);
      }
      const foundPart = string.substr(res.index, highlightPart.length) || "";
      if (foundPart.length > 0) {
        finalName.push(<span className={classes.highlight}>{foundPart}</span>);
      }
      lastIndex = res.index + highlightPart.length;
      i += 1;
    }
    const restPart = string.substr(lastIndex) || "";
    if (restPart.length > 0) {
      finalName.push(<span className={classes.regular}>{restPart}</span>);
    }
    setParts(finalName);
  }, [string, highlightPart]);

  return parts.length > 0 ? parts : string;
}

Highlight.propTypes = {
  string: PropTypes.string.isRequired,
  highlightPart: PropTypes.string.isRequired
};
