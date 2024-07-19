import React from "react";
import PropTypes from "prop-types";

function Size({ type, size, processes }) {
  if (Object.keys(processes).length > 0) {
    return null;
  }
  return (
    <td>
      {type === "file" && parseInt(size || "0", 10) > 0 ? (
        <span className="size">{size}</span>
      ) : null}
    </td>
  );
}

Size.propTypes = {
  processes: PropTypes.shape({
    [PropTypes.string]: PropTypes.shape({
      name: PropTypes.string,
      type: PropTypes.string,
      id: PropTypes.string
    })
  }),
  type: PropTypes.string.isRequired,
  size: PropTypes.oneOfType([PropTypes.string, PropTypes.number])
};

Size.defaultProps = {
  processes: {},
  size: 0
};
export default Size;
