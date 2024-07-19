/* eslint-disable react/prop-types */
import React, { useState } from "react";
import { makeStyles } from "@material-ui/core";
import { POST } from "../../../../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../../../../constants/validationSchemas/InputValidationFunctions";
import Requests from "../../../../utils/Requests";
import KudoButton from "../../../Inputs/KudoButton/KudoButton";
import KudoForm from "../../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../../Inputs/KudoInput/KudoInput";
import CommentInput from "./CommentInput";
import * as InputTypes from "../../../../constants/appConstants/InputTypes";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(1)}px !important`
  },
  submitButton: {
    float: "right"
  }
}));

export default function NewThread({ getThreads, onSubmit, fileId }) {
  const [text, setText] = useState("");
  const createThread = ({ title }) => {
    onSubmit();
    Requests.sendGenericRequest(
      `/files/${fileId}/commentThread`,
      POST,
      Requests.getDefaultUserHeaders(),
      { text, title: title.value }
    ).then(getThreads);
  };
  const classes = useStyles();
  return (
    <KudoForm
      id="createThread"
      validationFunction={() => true}
      onSubmitFunction={createThread}
    >
      <KudoInput
        formGroupClassName={classes.input}
        name="title"
        id="title"
        formId="createThread"
        type={InputTypes.TEXT}
        isEmptyValueValid={false}
        validationFunction={InputValidationFunctions.any}
        placeHolder="title"
        inputDataComponent="title-input"
      />
      <CommentInput className={classes.input} onTextChange={setText} />
      <KudoButton id="createThreadCancel" isDisabled={false} onClick={onSubmit}>
        Cancel
      </KudoButton>
      <KudoButton
        formId="createThread"
        isSubmit
        id="createThreadSubmit"
        className={classes.submitButton}
      >
        Create
      </KudoButton>
    </KudoForm>
  );
}
