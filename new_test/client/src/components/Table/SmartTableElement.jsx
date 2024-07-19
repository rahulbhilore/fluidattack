/* eslint-disable react/jsx-props-no-spreading */
/**
 * Created by Dima Graebert on 4/26/2017.
 */
import React from "react";
import PropTypes from "prop-types";
import _ from "underscore";

function SmartTableElement(props) {
  const { className, type, baseElement, children } = props;
  const newClassNames = `${type} ${className}`.trim();
  const additionalProps = _.extend(
    _.omit(props, "className", "baseElement", "children", "type"),
    { className: newClassNames }
  );
  if (baseElement === "Column") {
    return <td {...additionalProps}>{children}</td>;
  }
  if (baseElement === "Row") {
    return <tr {...additionalProps}>{children}</tr>;
  }
  return null;
}

SmartTableElement.propTypes = {
  type: PropTypes.string.isRequired,
  baseElement: PropTypes.string,
  className: PropTypes.string,
  children: PropTypes.oneOfType([
    PropTypes.node,
    PropTypes.arrayOf(PropTypes.node)
  ])
};

SmartTableElement.defaultProps = {
  className: "",
  baseElement: "NA",
  children: null
};

export default SmartTableElement;
