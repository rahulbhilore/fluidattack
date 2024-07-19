/* eslint-disable react/prop-types */
import React, { useState } from "react";
import {
  Card,
  CardHeader,
  CardContent,
  List,
  Collapse,
  Tooltip
} from "@material-ui/core";
import IconButton from "@material-ui/core/IconButton";
import ExpandMoreIcon from "@material-ui/icons/ExpandMore";
import DeleteIcon from "@material-ui/icons/Delete";
import CheckIcon from "@material-ui/icons/Check";
import clsx from "clsx";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Comment from "./Comment";
import NewComment from "./NewComment";
import Requests from "../../../../utils/Requests";
import {
  DELETE,
  PUT
} from "../../../../constants/appConstants/RequestsMethods";

const useStyles = makeStyles(theme => ({
  root: {
    marginTop: theme.spacing(2),
    backgroundColor: theme.palette.secondary.LIGHT,
    borderRadius: 5,
    cursor: "pointer"
  },
  heading: {
    padding: theme.spacing(1)
  },
  list: {
    padding: 0
  },
  expand: {
    transform: "rotate(0deg)",
    marginLeft: "auto",
    transition: theme.transitions.create("transform", {
      duration: theme.transitions.duration.shortest
    })
  },
  action: { marginTop: 0 },
  expandOpen: {
    transform: "rotate(180deg)"
  }
}));

export default function Thread({ threadInfo, fileId }) {
  const [expanded, setExpanded] = useState(false);
  const handleExpandClick = () => {
    setExpanded(!expanded);
  };
  const deleteThread = () => {
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThread/${threadInfo.id}?timestamp=${Date.now()}`,
      DELETE,
      Requests.getDefaultUserHeaders(),
      undefined
    ).then(() => {
      document.dispatchEvent(
        new CustomEvent("THREAD_UPDATED", {
          detail: { threadId: threadInfo.id, fileId }
        })
      );
    });
  };

  const resolveThread = () => {
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThread/${threadInfo.id}?timestamp=${Date.now()}`,
      PUT,
      Requests.getDefaultUserHeaders(),
      { state: "RESOLVED" }
    ).then(() => {
      document.dispatchEvent(
        new CustomEvent("THREAD_UPDATED", {
          detail: { threadId: threadInfo.id, fileId }
        })
      );
    });
  };
  const classes = useStyles();
  return (
    <Card className={classes.root}>
      <CardHeader
        title={threadInfo.title}
        className={classes.heading}
        classes={{ action: classes.action }}
        onClick={handleExpandClick}
        action={
          <>
            <Tooltip title="Resolve thread">
              <IconButton onClick={resolveThread} aria-label="resolve">
                <CheckIcon />
              </IconButton>
            </Tooltip>
            <Tooltip title="Delete thread">
              <IconButton onClick={deleteThread} aria-label="Delete">
                <DeleteIcon />
              </IconButton>
            </Tooltip>
            <Tooltip title="Expand">
              <IconButton
                className={clsx(classes.expand, {
                  [classes.expandOpen]: expanded
                })}
                onClick={handleExpandClick}
                aria-expanded={expanded}
                aria-label="show more"
              >
                <ExpandMoreIcon />
              </IconButton>
            </Tooltip>
          </>
        }
      />
      <Collapse in={expanded} timeout="auto" unmountOnExit>
        <CardContent className={classes.list}>
          <List>
            {threadInfo.comments
              .filter(c => c.state === "ACTIVE")
              .map((comment, i) => (
                <Comment
                  threadId={threadInfo.id}
                  fileId={fileId}
                  key={`${threadInfo.id}#${comment.id}`}
                  isOdd={i % 2 === 0}
                  comment={comment}
                />
              ))}
          </List>
          <NewComment threadId={threadInfo.id} fileId={fileId} />
        </CardContent>
      </Collapse>
    </Card>
  );
}
