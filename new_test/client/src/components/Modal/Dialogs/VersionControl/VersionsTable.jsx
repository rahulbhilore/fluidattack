import React, { useState } from "react";
import PropTypes from "prop-types";
import Immutable, { List } from "immutable";
import { makeStyles } from "@material-ui/core/styles";
import { SortDirection } from "react-virtualized";
import SmartTable from "../../../SmartTable/SmartTable";
import ProcessStore from "../../../../stores/ProcessStore";
import Processes from "../../../../constants/appConstants/Processes";
import filenameTable from "../../../SmartTable/tables/versions/Filename";
import modifiedTable from "../../../SmartTable/tables/versions/Modified";
import memberTable from "../../../SmartTable/tables/versions/Member";

const useStyles = makeStyles({
  smartTableContainer: {
    marginTop: "-20px",
    "& *": {
      color: "#000000"
    },
    "& .tableRow:nth-child(odd)": {
      backgroundColor: "#EDEDED"
    },
    "& .tableRow:nth-child(even)": {
      backgroundColor: "#F7F7F7"
    },
    "& .ReactVirtualized__Table .ReactVirtualized__Grid__innerScrollContainer .tableRow.selected":
      {
        background: "#D3D3D3!important"
      },
    "& .ReactVirtualized__Table .ReactVirtualized__Grid__innerScrollContainer .tableRow:hover":
      {
        background: "#D3D3D3!important"
      },
    "& .ReactVirtualized__Table__headerRow": {
      padding: "0!important"
    },
    "& .ReactVirtualized__Table .ReactVirtualized__Grid__innerScrollContainer .tableRow .tableColumn:nth-child(1)":
      {
        marginLeft: "4px"
      }
  }
});

const TOP_PROCESS_TYPES = [Processes.VERSION_UPLOAD];

const columns = new List([
  { dataKey: "filenameInfo", label: "versions", width: 0.3 },
  { dataKey: "creationTime", label: "modified", width: 0.35 },
  { dataKey: "member", label: "member", width: 0.35 }
]);

const presentation = new Immutable.Map({
  filenameInfo: filenameTable,
  creationTime: modifiedTable,
  member: memberTable
});

let sortDirection = SortDirection.DESC;

const sortByProcess = (a, b) => {
  if (ProcessStore.getProcessesSize() > 0) {
    const runningProcessesA = ProcessStore.getProcess(a.get("id"));
    const runningProcessesB = ProcessStore.getProcess(b.get("id"));
    if (!runningProcessesA && !runningProcessesB) return 0;
    const isAProcessTop = TOP_PROCESS_TYPES.includes(runningProcessesA?.type);
    const isBProcessTop = TOP_PROCESS_TYPES.includes(runningProcessesB?.type);
    if (isAProcessTop && !isBProcessTop)
      return sortDirection === SortDirection.ASC ? -1 : 1;
    if (isBProcessTop && !isAProcessTop)
      return sortDirection === SortDirection.ASC ? 1 : -1;
  }
  return 0;
};

const customSorts = {
  filenameInfo: (a, b) => {
    const processSort = sortByProcess(a, b);
    if (processSort !== 0) return processSort;
    return a
      .get("filenameInfo")
      .get("filename")
      .localeCompare(b.get("filenameInfo").get("filename"));
  },
  creationTime: (a, b) => {
    const processSort = sortByProcess(a, b);
    if (processSort !== 0) return processSort;
    return Number(a.get("creationTime")) > Number(b.get("creationTime"))
      ? 1
      : -1;
  },
  member: (a, b) => {
    const processSort = sortByProcess(a, b);
    if (processSort !== 0) return processSort;
    return a.get("member").localeCompare(b.get("member"));
  }
};

export default function VersionsTable({ versions, onTableLoaded }) {
  const classes = useStyles();
  const [isLoaded, setIsLoaded] = useState(false);

  const beforeSort = direction => {
    sortDirection = direction;
  };

  return (
    <div className={classes.smartTableContainer}>
      <SmartTable
        customSorts={customSorts}
        data={versions}
        columns={columns}
        defaultSortField="creationTime"
        sortDirection={SortDirection.DESC}
        presentation={presentation}
        tableType="versions"
        rowHeight={33}
        ref={ref => {
          if (isLoaded) return;
          if (!ref) return;
          onTableLoaded(ref.getTableId());
          setIsLoaded(true);
        }}
        isModal
        beforeSort={beforeSort}
      />
    </div>
  );
}

VersionsTable.propTypes = {
  versions: PropTypes.instanceOf(Immutable.List).isRequired,
  onTableLoaded: PropTypes.func.isRequired
};
