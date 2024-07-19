/* eslint-disable react/prop-types */
import React from "react";
import makeStyles from "@material-ui/core/styles/makeStyles";
import {
  Typography,
  ListItem,
  ListItemText,
  ListItemSecondaryAction,
  IconButton
} from "@material-ui/core";
import clsx from "clsx";
import DeleteIcon from "@material-ui/icons/Delete";
import RelativeTime from "../../../RelativeTime/RelativeTime";
import Requests from "../../../../utils/Requests";
import { DELETE } from "../../../../constants/appConstants/RequestsMethods";

const MENTION_REGEX = /\[ ([^\]]+) \| ([^\]]+) \]/gi;

const useStyles = makeStyles(theme => ({
  comment: {
    whiteSpace: "break-spaces",
    wordBreak: "break-all"
  },
  item: {
    padding: theme.spacing(1)
  },
  odd: {
    backgroundColor: theme.palette.secondary.main
  },
  mainText: {
    width: "100%"
  },
  additionalInfoBlock: {
    marginTop: theme.spacing(1)
  },
  additionalInfo: {
    fontSize: "0.8rem",
    display: "inline-block"
  },
  timestamp: {
    float: "right"
  },
  deleteIcon: {
    marginTop: `-${theme.spacing(3)}px`
  },
  mention: {
    color: theme.palette.SNOKE,
    fontWeight: "bold"
  }
}));

export default function Comment({ fileId, threadId, isOdd, comment }) {
  const classes = useStyles();
  const deleteComment = () => {
    document.dispatchEvent(
      new CustomEvent("COMMENT_DELETED", {
        detail: { threadId, fileId, commentId: comment.id }
      })
    );
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThread/${threadId}/comment/${
        comment.id
      }?timestamp=${Date.now()}`,
      DELETE,
      Requests.getDefaultUserHeaders(),
      { timestamp: Date.now() }
    ).then(() => {
      document.dispatchEvent(
        new CustomEvent("THREAD_UPDATED", {
          detail: { threadId, fileId }
        })
      );
    });
  };
  const parseCommentText = text => {
    let result;
    const endResult = [];
    let startPointer = 0;
    // eslint-disable-next-line no-cond-assign
    while ((result = MENTION_REGEX.exec(text))) {
      endResult.push(
        <span>{text.substr(startPointer, result.index - startPointer)}</span>
      );
      endResult.push(<span className={classes.mention}>*{result[1]}*</span>);
      startPointer = result.index + result[0].length;
    }
    endResult.push(<span>{text.substr(startPointer)}</span>);
    return endResult;
  };
  return (
    <ListItem className={clsx(classes.item, { [classes.odd]: isOdd })}>
      <ListItemText
        primary={
          <Typography variant="body1" className={classes.comment}>
            {parseCommentText(comment.text)}
          </Typography>
        }
        secondary={
          <div className={classes.additionalInfoBlock}>
            <Typography variant="body2" className={classes.additionalInfo}>
              {comment.author.name}
            </Typography>
            <Typography
              variant="body2"
              className={clsx(classes.additionalInfo, classes.timestamp)}
            >
              <RelativeTime timestamp={comment.timestamp} />
            </Typography>
          </div>
        }
      />
      <ListItemSecondaryAction>
        <IconButton
          edge="end"
          aria-label="delete"
          onClick={deleteComment}
          className={classes.deleteIcon}
        >
          <DeleteIcon />
        </IconButton>
      </ListItemSecondaryAction>
    </ListItem>
  );
}
