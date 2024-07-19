import React, { useEffect, useState } from "react";
import Drawer from "@material-ui/core/Drawer";
import IconButton from "@material-ui/core/IconButton";
import Toolbar from "@material-ui/core/Toolbar";
import Tooltip from "@material-ui/core/Tooltip";
import makeStyles from "@material-ui/core/styles/makeStyles";
import AddBoxIcon from "@material-ui/icons/AddBox";
import RefreshIcon from "@material-ui/icons/Refresh";
import Requests from "../../../../utils/Requests";
import { GET } from "../../../../constants/appConstants/RequestsMethods";
import Thread from "./Thread";
import Loader from "../../../Loader";
import NewThread from "./NewThread";

const regex = /\[(.*?)(<.*?>\|.*?)\]/g;
const useStyles = makeStyles(theme => ({
  root: {
    width: "30%",
    padding: theme.spacing(1),
    overflow: "auto",
    backgroundColor: theme.palette.VENERO,
    zIndex: theme.zIndex.modal + 2,
    [theme.breakpoints.down("sm")]: {
      width: "100%"
    }
  },
  toolbar: {
    padding: 0,
    minHeight: 0
  }
}));

const formatMentionData = thread => {
  const updatedComments = [];
  const { comments } = thread;
  comments.forEach(comment => {
    const { text } = comment;
    let newTargetText = text.replace(regex, (match, $1) => `@${$1}`);
    newTargetText = newTargetText.replaceAll("[", "").replaceAll("]", "");
    comment.text = newTargetText;
    updatedComments.push(comment);
  });
  thread.comments = updatedComments;
  return thread;
};

// eslint-disable-next-line react/prop-types
export default function Comments({ fileId }) {
  const [open, setOpen] = useState(false);
  const [isLoading, setloading] = useState(true);
  const [threads, _setThreads] = useState([]);
  // https://medium.com/geographit/accessing-react-state-in-event-listeners-with-usestate-and-useref-hooks-8cceee73c559
  const threadsRef = React.useRef(threads);
  const setThreads = data => {
    threadsRef.current = data;
    _setThreads(data);
  };

  const [threadCreation, setThreadCreation] = useState(false);
  const close = () => {
    setOpen(false);
  };
  useEffect(() => {
    window.AKOpenTestComments = () => {
      setOpen(true);
    };
    return () => {
      delete window.AKOpenTestComments;
    };
  }, []);
  const getThreads = () => {
    setThreads([]);
    setloading(true);
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThreads`,
      GET,
      Requests.getDefaultUserHeaders()
    ).then(response => {
      setThreads(
        response.data.commentThreads
          .filter(thread => thread.state === "ACTIVE")
          .map(thread => formatMentionData(thread))
      );
      setloading(false);
    });
  };
  const updateThread = threadId => {
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThread/${threadId}`,
      GET,
      Requests.getDefaultUserHeaders()
    ).then(response => {
      setThreads(
        threadsRef.current
          .map(t => (t.id === threadId ? response.data : t))
          .filter(thread => thread.state === "ACTIVE")
          .map(thread => formatMentionData(thread))
      );
    });
  };
  useEffect(() => {
    if (open === true) {
      getThreads();
      document.addEventListener("THREAD_UPDATED", e => {
        const { fileId: eventFileId, threadId } = e.detail;
        if (eventFileId === fileId) {
          updateThread(threadId);
        }
      });
      document.addEventListener("COMMENT_DELETED", e => {
        const { fileId: eventFileId, threadId, commentId } = e.detail;
        if (eventFileId === fileId) {
          setThreads(
            threadsRef.current
              .map(t => {
                if (t.id === threadId) {
                  t.comments = t.comments.filter(c => c.id !== commentId);
                }
                return t;
              })
              .filter(thread => thread.state === "ACTIVE")
          );
        }
      });
    }
  }, [open]);
  const invokeThreadCreation = () => {
    setThreadCreation(true);
  };
  const finishThreadCreation = () => {
    setThreadCreation(false);
  };
  const classes = useStyles();
  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={close}
      classes={{ paper: classes.root }}
    >
      <Toolbar className={classes.toolbar}>
        <Tooltip title="Create thread">
          <IconButton aria-label="createThread" onClick={invokeThreadCreation}>
            <AddBoxIcon />
          </IconButton>
        </Tooltip>
        <Tooltip title="Refresh">
          <IconButton aria-label="refresh" onClick={getThreads}>
            <RefreshIcon />
          </IconButton>
        </Tooltip>
      </Toolbar>
      {threadCreation ? (
        <NewThread
          getThreads={getThreads}
          onSubmit={finishThreadCreation}
          fileId={fileId}
        />
      ) : null}
      <span style={{ overflow: "auto" }}>
        {isLoading ? (
          <Loader />
        ) : (
          threads.map(threadInfo => (
            <Thread
              threadInfo={threadInfo}
              key={threadInfo.id}
              fileId={fileId}
            />
          ))
        )}
      </span>
    </Drawer>
  );
}
