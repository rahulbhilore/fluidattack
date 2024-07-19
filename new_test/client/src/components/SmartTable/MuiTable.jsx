import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { Table } from "react-virtualized";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Box } from "@material-ui/core";
import LinearProgress from "@material-ui/core/LinearProgress";

const useStyles = makeStyles(theme => ({
  root: {
    display: "inline-block"
  },
  table: {
    "& .noDataRow": {
      cursor: "default !important",
      color: theme.palette.JANGO,
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      alignItems: "center",
      height: "100%"
    },
    "& .tableRow": {
      cursor: "pointer",
      boxShadow: `inset 0 1px 0 0 ${theme.palette.GREY_TEXT}`,
      border: "none",
      "&:last-child": {
        borderBottom: `solid 1px ${theme.palette.BREADCRUMB_BOTTOM_BORDER}`
      },
      "&.selected": {
        background: theme.palette.BREADCRUMB_BOTTOM_BORDER,
        transitionDuration: "0.1s"
      },
      "&.highlighted": {
        transitionDuration: ".15s",
        backgroundColor: "#c1f5cf !important"
      },
      "&.uploaded": {
        backgroundColor: "#c1f5cf !important"
      },
      "&.uploading": {
        backgroundColor: "#c1f5cf !important"
      },
      "&:hover": {
        backgroundColor: theme.palette.BREADCRUMB_BOTTOM_BORDER
      },
      "& .tableColumn": {
        fontFamily: String(theme.typography.fontFamily),
        fontSize: theme.typography.body1.fontSize,
        color: theme.palette.VADER,
        fontWeight: 400,
        cursor: "pointer",
        border: "none !important",
        userSelect: "none",
        "& .process_percentage": {
          "& .wide_percentage": {
            width: "40% !important"
          },
          "& .process_caption": {
            color: `${theme.palette.OBI} !important`,
            fontSize: theme.typography.body1.fontSize,
            fontWeight: "bold",
            "&.uploadComplete": {
              fontWeight: "normal"
            },
            "&.canceled": {
              fontWeight: "normal",
              color: theme.palette.CLONE
            }
          }
        },
        "& .progressBar": {
          width: "38% !important",
          paddingRight: "100px !important",
          "& .uploadIconOverlay": {
            margin: "0 25px",
            display: "inline-block",
            width: "20px",
            height: "20px",
            verticalAlign: "middle",
            "& .uploadIcon > svg": {
              verticalAlign: "top"
            }
          }
        }
      }
    },
    "& .ReactVirtualized__Table__headerRow": {
      width: "100%",
      "& .ReactVirtualized__Table__headerColumn": {
        color: theme.palette.JANGO,
        cursor: "pointer",
        padding: 0
      }
    }
  },
  progress: {
    height: "3px",
    "&.MuiLinearProgress-colorPrimary": {
      backgroundColor: theme.palette.GREY_TEXT
    },
    "& .MuiLinearProgress-bar1Indeterminate": {
      animation: "$cssload-width 4.5s cubic-bezier(0.45, 0, 1, 1) infinite",
      backgroundColor: theme.palette.KYLO
    },
    "& .MuiLinearProgress-bar2Indeterminate": {
      animation: "none"
    },
    "&.files": {
      position: "absolute",
      width: "100%",
      bottom: 0
    }
  },
  "@keyframes cssload-width": {
    "0%": {
      width: 0
    },
    "100%": {
      transitionTimingFunction: "cubic-bezier(1, 0, 0.65, 0.85)",
      width: "100%"
    }
  }
}));

function MuiTable(props) {
  const styles = useStyles();
  const {
    tableType,
    isLazy,
    inputRef,
    classes: propClasses,
    ...otherProps
  } = props;
  return (
    <Box className={clsx(styles.root, propClasses.box)}>
      <Table
        className={clsx(styles.table, tableType, propClasses.table)}
        ref={inputRef}
        data-component="table"
        // eslint-disable-next-line react/jsx-props-no-spreading
        {...otherProps}
      />
      {isLazy ? (
        <LinearProgress
          variant="indeterminate"
          className={clsx(styles.progress, tableType)}
        />
      ) : null}
    </Box>
  );
}

MuiTable.propTypes = {
  tableType: PropTypes.string,
  isLazy: PropTypes.bool,
  inputRef: PropTypes.func.isRequired,
  classes: PropTypes.shape({
    box: PropTypes.string,
    table: PropTypes.string
  })
};

MuiTable.defaultProps = {
  tableType: "",
  isLazy: false,
  classes: {
    box: "",
    table: ""
  }
};
export default React.memo(MuiTable);
