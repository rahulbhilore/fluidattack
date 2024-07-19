/* eslint-disable react/prop-types */
import React, { useState } from "react";
import makeStyles from "@material-ui/core/styles/makeStyles";
import KudoButton from "../../../Inputs/KudoButton/KudoButton";
import KudoForm from "../../../Inputs/KudoForm/KudoForm";
import Requests from "../../../../utils/Requests";
import { POST } from "../../../../constants/appConstants/RequestsMethods";
import CommentInput from "./CommentInput";

const useStyles = makeStyles(theme => ({
  root: {
    padding: theme.spacing(1)
  },
  input: {
    marginBottom: theme.spacing(1)
  }
}));

export default function NewComment({ threadId, fileId }) {
  const formId = `newComment#${threadId}`;
  const [text, setText] = useState("");
  const postComment = () => {
    document.dispatchEvent(
      new CustomEvent("COMMENT_CREATED", {
        detail: {
          threadId,
          fileId,
          comment: {
            timestamp: Date.now(),
            text,
            author: { name: "ME" },
            state: "ACTIVE"
          }
        }
      })
    );
    setText("");
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThread/${threadId}/comment`,
      POST,
      Requests.getDefaultUserHeaders(),
      { text }
    ).then(() => {
      document.dispatchEvent(
        new CustomEvent("THREAD_UPDATED", { detail: { threadId, fileId } })
      );
    });
  };
  const classes = useStyles();
  return (
    <KudoForm
      id={formId}
      onSubmitFunction={postComment}
      classNames={classes.root}
      validationFunction={() => true}
    >
      <CommentInput
        className={classes.input}
        onTextChange={setText}
        text={text}
        isControlled
      />
      <KudoButton isSubmit formId={formId} isDisabled={false}>
        Create
      </KudoButton>
    </KudoForm>
  );
}
