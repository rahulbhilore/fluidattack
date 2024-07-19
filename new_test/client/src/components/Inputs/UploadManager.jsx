import {
  IconButton,
  LinearProgress,
  makeStyles,
  Tooltip,
  Typography,
  useTheme
} from "@material-ui/core";
import CloseIcon from "@material-ui/icons/Close";
import CheckIcon from "@material-ui/icons/Check";
import CachedIcon from "@material-ui/icons/Cached";
import ClearIcon from "@material-ui/icons/Clear";
import React, { useEffect, useRef, useState } from "react";
import { useIntl } from "react-intl";
import $ from "jquery";
import drawingSVG from "../../assets/images/icons/drawing.svg";
import uploadStore, {
  UPLOADS_QUEUE_UPDATE,
  UPLOAD_STATUSES
} from "../../stores/UploadStore";
import newRequests from "../../utils/Requests";
import UploadActions from "../../actions/UploadActions";
import MainFunctions from "../../libraries/MainFunctions";

export const UPLOAD_OBJECTS = "UPLOAD_OBJECTS";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.LIGHT,
    position: "absolute",
    bottom: theme.spacing(1),
    right: theme.spacing(1),
    width: "300px",
    boxShadow: "0 3px 1px rgb(0 0 0 / 8%)"
  },
  toolbar: {
    backgroundColor: theme.palette.SNOKE,
    padding: theme.spacing(1, 2),
    height: "40px",
    display: "flex",
    justifyContent: "space-between",
    alignItems: "center",
    borderTopLeftRadius: "4px",
    borderTopRightRadius: "4px"
  },
  caption: {
    fontSize: theme.typography.pxToRem(14)
  },
  icon: {
    width: "20px",
    height: "20px"
  },
  fileBlock: {
    padding: theme.spacing(1, 2),
    height: "50px",
    display: "flex",
    alignItems: "center",
    flexDirection: "row",
    gap: theme.spacing(1),
    cursor: "pointer",
    "&:hover,&:focus,&:active": {
      backgroundColor: theme.palette.REY
    }
  },
  middleFileBlock: {
    display: "flex",
    flexDirection: "column",
    gap: theme.spacing(0.5),
    flex: 1
  },
  thumbnailIcon: {
    maxHeight: "25px"
  },
  fileName: {
    fontSize: theme.typography.pxToRem(12),
    color: theme.palette.SNOKE,
    flex: "1"
  },
  status: {},
  uploadsBlock: {
    maxHeight: "300px",
    overflowY: "auto"
  },
  progress: {
    height: "10px",
    backgroundColor: theme.palette.REY,
    display: "inline-block",
    verticalAlign: "middle"
  },
  bar: {
    backgroundColor: theme.palette.OBI
  }
}));

export default function UploadManager() {
  const classes = useStyles();
  const theme = useTheme();
  const [files, setFiles] = useState(uploadStore.getUploadsQueue());
  const [_requestPercentage, _setRequestPercentage] = useState({});
  const requestPercentage = useRef(_requestPercentage);
  const setRequestPercentage = requestGenerator => {
    const newVal = requestGenerator();
    _setRequestPercentage(newVal);
    requestPercentage.current = newVal;
  };
  const intl = useIntl();

  const onQueueUpdate = () => {
    setFiles(uploadStore.getUploadsQueue());
  };
  useEffect(() => {
    uploadStore.addListener(UPLOADS_QUEUE_UPDATE, onQueueUpdate);
    return () => {
      uploadStore.removeListener(UPLOADS_QUEUE_UPDATE, onQueueUpdate);
    };
  }, []);

  const processQueuedRequests = async () => {
    const filesPerStatus = files.groupBy(f => f.get("status"));
    const queuedRequests = filesPerStatus.get(UPLOAD_STATUSES.QUEUED) || [];
    if (queuedRequests?.size > 0) {
      queuedRequests.forEach(requestToPerform => {
        UploadActions.setRequestInProgress(requestToPerform.get("requestId"));
      });
      const chunks = MainFunctions.splitImmutableMapIntoChunks(queuedRequests);
      // eslint-disable-next-line no-restricted-syntax
      for (const chunk of chunks) {
        chunk.forEach(requestToPerform => {
          const oldStyleHeaders = [];
          Object.entries(requestToPerform.get("headers").toJS()).forEach(
            ([name, value]) => {
              oldStyleHeaders.push({ name, value });
            }
          );
          let timer = Date.now();
          newRequests.uploadFile(
            (window.ARESKudoConfigObject.api ||
              window.ARESKudoConfigObject.apiURL) + requestToPerform.get("url"),
            requestToPerform.get("body"),
            oldStyleHeaders,
            response => {
              if (response.status === 413) {
                UploadActions.setRequestError(
                  requestToPerform.get("requestId"),
                  "Entity is too large"
                );
              } else if (response.status === 429) {
                UploadActions.setRequestError(
                  requestToPerform.get("requestId"),
                  MainFunctions.safeJSONParse(response.error || response.data)
                    ?.message || "Please retry later"
                );
              } else if (response.status === 403) {
                try {
                  const jsonMessage = JSON.parse(
                    response.error || response.data
                  );
                  if (jsonMessage.errorId === "KD1") {
                    UploadActions.setRequestError(
                      requestToPerform.get("requestId"),
                      intl.formatMessage({
                        id: "cannotUploadFilesKudoDriveIsFull"
                      })
                    );
                  } else {
                    UploadActions.setRequestError(
                      requestToPerform.get("requestId"),
                      MainFunctions.safeJSONParse(
                        response.error || response.data
                      )?.message || "No rights to upload"
                    );
                  }
                } catch (ex) {
                  UploadActions.setRequestError(
                    requestToPerform.get("requestId"),
                    response.error?.message ||
                      response.data?.message ||
                      "Unknown error"
                  );
                }
              } else if (response.status !== 200) {
                UploadActions.setRequestError(
                  requestToPerform.get("requestId"),
                  response.error?.message ||
                    response.data?.message ||
                    "Unknown error"
                );
              } else {
                UploadActions.setRequestFinalized(
                  requestToPerform.get("requestId"),
                  response.data
                );
              }
            },
            null,
            progressEvt => {
              if (
                !progressEvt.event ||
                !progressEvt.event.lengthComputable ||
                progressEvt.event.lengthComputable !== true
              ) {
                return;
              }
              const loaded = progressEvt.loaded / progressEvt.total;
              if (loaded !== 1 && Date.now() - timer >= 100) {
                const percentage = (loaded * 100).toFixed(2);
                setRequestPercentage(prevValue => ({
                  ...prevValue,
                  [requestToPerform.get("requestId")]: percentage
                }));
                timer = Date.now();
              }
            }
          );
        });
        // we need to wait for a bit to make sure we don't hit limits
        // eslint-disable-next-line no-await-in-loop
        await new Promise(resolve => {
          setTimeout(() => {
            resolve();
          }, 5 * 1000);
        });
      }
    }
  };

  useEffect(() => {
    processQueuedRequests();
  }, [files]);

  const filesPerStatus = files.groupBy(f => f.get("status"));
  let title = "Upload complete";
  if (filesPerStatus.get(UPLOAD_STATUSES.IN_PROGRESS)?.size > 0) {
    title = `Uploading ${
      filesPerStatus.get(UPLOAD_STATUSES.IN_PROGRESS).size
    } files`;
  }
  if (!files.size) return null;
  return (
    <div className={classes.root}>
      <div className={classes.toolbar}>
        <Typography className={classes.caption}>{title}</Typography>
        <IconButton className={classes.icon} onClick={UploadActions.clearList}>
          <CloseIcon />
        </IconButton>
      </div>
      <div className={classes.uploadsBlock}>
        {files.valueSeq().map(file => {
          const truncatedFileName = MainFunctions.shrinkString(
            file.get("name"),
            30
          );
          const middleFileBlock = (
            <div className={classes.middleFileBlock}>
              <Typography className={classes.fileName}>
                {truncatedFileName}
              </Typography>

              {file.get("status") === UPLOAD_STATUSES.IN_PROGRESS ? (
                <LinearProgress
                  classes={{ root: classes.progress, bar: classes.bar }}
                  variant="determinate"
                  value={requestPercentage.current[file.get("requestId")] || 0}
                />
              ) : null}
            </div>
          );
          return (
            <div className={classes.fileBlock} key={file.get("id")}>
              <img
                src={drawingSVG}
                alt="TEST"
                className={classes.thumbnailIcon}
              />
              {truncatedFileName.length < file.get("name").length ? (
                <Tooltip placement="top" title={file.get("name")}>
                  {middleFileBlock}
                </Tooltip>
              ) : (
                middleFileBlock
              )}
              {/* status */}
              {file.get("status") === UPLOAD_STATUSES.IN_PROGRESS ? (
                <CachedIcon style={{ color: theme.palette.OBI }} />
              ) : null}
              {file.get("status") === UPLOAD_STATUSES.DONE &&
              !file.has("error") ? (
                <CheckIcon style={{ color: theme.palette.YODA }} />
              ) : null}
              {file.get("status") === UPLOAD_STATUSES.DONE &&
              file.has("error") ? (
                <Tooltip placement="top" title={file.get("error")}>
                  <ClearIcon style={{ color: theme.palette.KYLO }} />
                </Tooltip>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}
