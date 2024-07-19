import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import Box from "@material-ui/core/Box";
import Typography from "@material-ui/core/Typography";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import UserInfoStore from "../../../../stores/UserInfoStore";
import UploadProgress from "./innerComponents/UploadProgress";
import ProcessStore from "../../../../stores/ProcessStore";
import RelativeTime from "../../../RelativeTime/RelativeTime";
import * as ProcessConstants from "../../../../constants/ProcessContants";
import FilesListActions from "../../../../actions/FilesListActions";

type StorageDataType = { [key: string]: string };
type ProcessInfoType = { status: string; type: string };
type ObjectData = {
  processId: string;
  changer: string;
  changerId: string;
  changerEmail: string;
  type: string;
  deleted: boolean;
  updateDate: number;
  creationDate: number;
};

const useStyles = makeStyles(theme => ({
  modifyDate: {
    marginRight: "10px",
    lineHeight: "80px",
    whiteSpace: "nowrap",
    display: "inline-block"
  },
  modifiedInfo: {
    // @ts-ignore
    color: theme.palette.DARK,
    fontSize: "12px",
    fontWeight: "bold",
    display: "inline-block"
  },
  processBox: {
    position: "absolute",
    width: "25vw",
    top: 10
  }
}));

function Modified({ _id, data }: { _id: string; data: ObjectData }) {
  const classes = useStyles();
  const { processId } = data;
  const [id, setId] = useState(_id);

  const [process, setProcess] = useState(
    ProcessStore.isProcess(_id) ? ProcessStore.getProcess(_id) : true
  );

  useEffect(() => {
    if (id !== _id) {
      setProcess(false);
      setId(_id);
    }
  }, [_id]);

  const handleProcess = (processInfo: ProcessInfoType) => {
    switch (processInfo.status) {
      case ProcessConstants.START:
        if (
          processInfo.type &&
          (processInfo.type.toLowerCase() === "preparing" ||
            processInfo.type.toLowerCase().includes("upload"))
        )
          setProcess(processInfo);
        break;
      case ProcessConstants.STEP:
        setProcess(processInfo);
        break;
      case ProcessConstants.END:
        setProcess(false);
        break;
      default:
        break;
    }
  };

  useEffect(() => {
    ProcessStore.addChangeListener(processId || id, handleProcess);
    return () => {
      ProcessStore.removeChangeListener(processId || id, handleProcess);
    };
  }, [id, data]);

  let storageUserName: string;
  const { id: storageId, type: storageName } =
    UserInfoStore.getUserInfo("storage");
  if (storageName) {
    const storageAccountsInfo = UserInfoStore.getStoragesInfo()[storageName];
    if (storageAccountsInfo) {
      const storageInfo = storageAccountsInfo.find(
        (storage: StorageDataType) => storage[`${storageName}_id`] === storageId
      );
      if (storageInfo) {
        storageUserName = storageInfo[`${storageName}_username`];
      }
    }
  }
  const getChangerName = () => {
    if (!storageName || !storageUserName) {
      return "";
    }
    const { changer, changerId, changerEmail, type } = data;
    if (
      ((type === "file" && changer === UserInfoStore.getUserInfo("username")) ||
        changer ===
          `${UserInfoStore.getUserInfo("name")} ${UserInfoStore.getUserInfo(
            "surname"
          )}`) &&
      (!changerId || storageName === "samples"
        ? UserInfoStore.getUserInfo("id") === changerId
        : storageId === changerId) &&
      (!changerEmail || storageUserName === changerEmail)
    ) {
      return <FormattedMessage id="me" />;
    }
    return changer;
  };

  const cancelUploadingProcess = () => {
    if (!process || (!process.uploadId && !process.id)) return;
    FilesListActions.cancelEntityUpload(process.uploadId || process.id);
  };

  const { deleted = false, updateDate, creationDate } = data;

  if (process) {
    if (Object.keys(process).length > 0) {
      // should we use multiprocessing as well?
      // const singleProcess = Object.values(processes)[0];
      return (
        <Box className={classes.processBox}>
          <UploadProgress
            value={parseFloat(process.value)}
            name={process.type}
            id={process.id}
            cancelFunction={cancelUploadingProcess}
          />
        </Box>
      );
    }
  }

  if (deleted && UserInfoStore.isFeatureAllowedByStorage("inlineTrash")) {
    return <div />;
  }

  return (
    <div>
      <Box className={classes.modifyDate}>
        {(updateDate || creationDate || 0) !== 0 ? (
          <RelativeTime timestamp={updateDate || creationDate || 0} />
        ) : (
          <Typography>{String.fromCharCode(8212)}</Typography>
        )}
      </Box>
      <Box className={classes.modifiedInfo}>{getChangerName()}</Box>
    </div>
  );
}

Modified.propTypes = {
  _id: PropTypes.string.isRequired,
  data: PropTypes.shape({
    type: PropTypes.string,
    name: PropTypes.string,
    mimeType: PropTypes.string,
    public: PropTypes.bool,
    permissions: PropTypes.shape({
      canManagePermissions: PropTypes.bool
    }),
    processId: PropTypes.string,
    changer: PropTypes.string,
    changerId: PropTypes.string,
    changerEmail: PropTypes.string,
    deleted: PropTypes.bool,
    updateDate: PropTypes.number,
    creationDate: PropTypes.number
  }).isRequired
};

export default React.memo(Modified, (prevProps, nextProps) => {
  if (
    prevProps._id === nextProps._id &&
    _.isEqual(prevProps.data, nextProps.data)
  )
    return true;
  return false;
});
