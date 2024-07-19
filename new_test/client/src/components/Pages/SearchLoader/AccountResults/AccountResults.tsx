import React, { useCallback, useState } from "react";
import { makeStyles } from "@material-ui/core/styles";
import Immutable, { List } from "immutable";
import { ColumnProps, SortDirection } from "react-virtualized";
import { Box } from "@mui/material";
import Name from "../../../SmartTable/tables/files/Name";
import Access from "../../../SmartTable/tables/files/Access";
import Modified from "../../../SmartTable/tables/files/Modified";
import Size from "../../../SmartTable/tables/files/Size";
import Breadcrumbs from "../../../Breadcrumbs/Breadcrumbs";
import SmartTable from "../../../SmartTable/SmartTable";
import ModalActions from "../../../../actions/ModalActions";
import TableSorts from "../../../SmartTable/types/TableSorts";
import ClientFile from "../../../../stores/objects/types/ClientFile";
import ClientFolder from "../../../../stores/objects/types/ClientFolder";
import { FileObject, sortByName } from "../../../../utils/FileSort";
import TablePresentations from "../../../SmartTable/types/TablePresentations";

const useStyles = makeStyles(theme => ({
  box: {
    padding: theme.spacing(2)
  },
  table: {
    "& .ReactVirtualized__Table__headerRow .ReactVirtualized__Table__headerColumn":
      {
        fontSize: theme.typography.pxToRem(11)
      },
    "& .ReactVirtualized__Table__sortableHeaderIcon": {
      width: "22px",
      height: "22px",
      verticalAlign: "middle"
    },
    "& .ReactVirtualized__Table__Grid .noDataRow": {
      // @ts-ignore
      color: theme.palette.JANGO
    },
    "& .ReactVirtualized__Table__rowColumn:nth-of-type(2)": {
      overflow: "visible!important"
    }
  }
}));

type SetDimensionsFunction = () => number;

const columns: List<ColumnProps> = List([
  { dataKey: "name", label: "name", width: 0.4 },
  { dataKey: "access", label: "access", width: 0.1 },
  { dataKey: "modified", label: "modified", width: 0.3 },
  { dataKey: "size", label: "size", width: 0.2 }
]);

// @ts-ignore because of Name class component
const presentations: TablePresentations = Immutable.Map({
  name: Name,
  access: Access,
  modified: Modified,
  size: Size
});

const customSorts: TableSorts<unknown> = {
  name: (
    a: Immutable.Map<string, FileObject>,
    b: Immutable.Map<string, FileObject>
  ) =>
    sortByName(
      a.toJS() as unknown as FileObject,
      b.toJS() as unknown as FileObject,
      SortDirection.ASC
    )
};

const TABLE_ROW_HEIGHT = 80;
const TABLE_ROW_MARGIN = 30;

interface Props {
  storageName: string;
  folderId: string;
  externalId: string;
  results: (ClientFile | ClientFolder)[];
}

interface PathItem {
  _id: string;
  name: string;
}

const AccountResults: React.FC<Props> = ({
  results,
  externalId,
  folderId,
  storageName
}) => {
  const classes = useStyles();

  const setTableDimensions: SetDimensionsFunction = useCallback(
    () => results.length * TABLE_ROW_HEIGHT + TABLE_ROW_MARGIN,
    [results]
  );

  const handleDeleteFiles = useCallback((ids: string[]) => {
    const objectsToDelete = ids.reduce(
      (
        reducingIds: { id: string; _id: string; name: string; type: string }[],
        id
      ) => {
        const foundObject = results.find(object => object.id === id);

        if (foundObject) {
          reducingIds.push({
            id: foundObject.id || foundObject._id,
            _id: foundObject.id || foundObject._id,
            name: foundObject.name,
            type: foundObject.type
          });
        }

        return reducingIds;
      },
      []
    );

    ModalActions.deleteObjects("files", objectsToDelete);
  }, []);

  const [boxPath, setBoxPath] = useState("");
  const handlePathUpdate = useCallback((newPath: Array<PathItem>) => {
    setBoxPath(newPath.map(item => item.name).join("/"));
  }, []);

  return (
    <Box data-object-name={boxPath}>
      <Breadcrumbs
        targetId={folderId}
        className="search"
        storage={{
          type: storageName,
          id: externalId
        }}
        onPathChange={handlePathUpdate}
      />
      <SmartTable
        customSorts={customSorts}
        columns={columns}
        presentation={presentations}
        tableType="search"
        data={Immutable.fromJS(results)}
        rowHeight={80}
        widthPadding={32}
        classes={classes}
        handleDelete={handleDeleteFiles}
        setTableDimensions={setTableDimensions}
      />
    </Box>
  );
};

export default AccountResults;
