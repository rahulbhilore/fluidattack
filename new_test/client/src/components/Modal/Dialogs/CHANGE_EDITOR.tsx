import React, { useCallback, useState } from "react";
import {
  InputLabel,
  InputAdornment,
  OutlinedInput,
  outlinedInputClasses,
  Alert,
  Box,
  TextField,
  FormGroup,
  styled
} from "@mui/material";
import { SubmitHandler, useForm } from "react-hook-form";
import KudoCheckbox from "../../Inputs/KudoCheckboxNext/KudoCheckbox";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import ApplicationStore from "../../../stores/ApplicationStore";
import Storage from "../../../utils/Storage";
import KudoButton from "../../Inputs/KudoButtonNext/KudoButton";
import AccessToken from "../../Pages/DrawingLoader/AccessToken";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import useTranslate from "../../../hooks/useTranslate";

const SmallLabel = styled(InputLabel)(({ theme }) => ({
  color: theme.palette.JANGO,
  fontSize: theme.typography.pxToRem(12)
}));

const TextInput = styled(TextField)(({ theme }) => ({
  [`& .${outlinedInputClasses.root}`]: {
    borderRadius: 0
  },
  [`& .${outlinedInputClasses.input}`]: {
    color: theme.palette.JANGO,
    padding: theme.spacing(0, 1),
    height: "36px",
    fontSize: theme.typography.pxToRem(12)
  }
}));

const OutlinedTextInput = styled(OutlinedInput)(({ theme }) => ({
  [`&.${outlinedInputClasses.root}`]: {
    borderRadius: 0,
    paddingRight: 0,
    [`& .${outlinedInputClasses.input}`]: {
      color: theme.palette.CLONE,
      padding: theme.spacing(0, 1),
      height: "36px",
      fontSize: theme.typography.pxToRem(12)
    }
  }
}));

type ChangeEditorInputs = {
  editorURL: string;
  alwaysEdit: boolean;
  additionalParameters: string;
};

export default function changeEditor() {
  const [currentFileURL, setCurrentURL] = useState(
    Storage.getItem("currentURL") ||
      Storage.getItem("customEditorURL") ||
      ApplicationStore.getApplicationSetting("editorURL")
  );

  const [isCurrentURLUpdating, setCurrentURLUpdating] = useState(false);

  const { t } = useTranslate();

  const { register, handleSubmit, getValues } = useForm<ChangeEditorInputs>({
    defaultValues: {
      editorURL:
        Storage.getItem("customEditorURL") ||
        ApplicationStore.getApplicationSetting("editorURL"),
      additionalParameters: JSON.stringify(
        JSON.parse(Storage.getItem("additionalParameters") || "{}"),
        null,
        2
      ),
      alwaysEdit: Storage.getItem("alwaysEdit") === "true"
    }
  });

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(currentFileURL);
      SnackbarUtils.alertInfo({ id: "copiedToClipboard" });
    } catch (ex) {
      // eslint-disable-next-line no-console
      console.error(`[COPY] Couldn't copy to clipboard`, ex);
      SnackbarUtils.alertError({ id: "cannotCopyToClipboard" });
    }
  }, [currentFileURL]);

  const produceCurrentURL = useCallback(() => {
    if (isCurrentURLUpdating) return;
    setCurrentURLUpdating(true);
    const values = getValues();
    const baseParameters = JSON.parse(
      Storage.getItem("baseEditorParameters") || "{}"
    );
    const finalParameters = AccessToken.getProperParameters(
      Storage.getItem("currentFileId") || "",
      {
        ...baseParameters,
        ...JSON.parse(values.additionalParameters || "{}")
      }
    );

    AccessToken.generateWithURL(values.editorURL, finalParameters).then(
      endLink => {
        setCurrentURL(endLink);
        setCurrentURLUpdating(false);
      }
    );
  }, [getValues, setCurrentURL, setCurrentURLUpdating, isCurrentURLUpdating]);

  const onFormSubmit: SubmitHandler<ChangeEditorInputs> = useCallback(
    values => {
      Storage.setItem("customEditorURL", values.editorURL);
      try {
        Storage.setItem(
          "additionalParameters",
          JSON.stringify(JSON.parse(values.additionalParameters))
        );
      } catch (ex) {
        // eslint-disable-next-line no-console
        console.error(`[ADDITIONAL PARAMS] error on submit`, ex);
        SnackbarUtils.alertError({
          id: "changeEditorDialog.incorrectJSONProvided"
        });
      }
      const { alwaysEdit } = values;
      if (alwaysEdit) {
        Storage.setItem("alwaysEdit", "true");
      } else {
        Storage.deleteValue("alwaysEdit");
      }
      FilesListActions.reloadDrawing(
        FilesListStore.getCurrentFile().viewOnly,
        true,
        false
      );
      ModalActions.hide();
    },
    []
  );

  return (
    <form onSubmit={handleSubmit(onFormSubmit)}>
      <DialogBody>
        <Box
          sx={{
            display: "flex",
            flexDirection: "column",
            gap: 2
          }}
        >
          <FormGroup>
            <SmallLabel htmlFor="editorURL">
              {t("changeEditorDialog.baseURL")}
            </SmallLabel>
            <TextInput {...register("editorURL")} id="editorURL" />
          </FormGroup>
          <FormGroup>
            <SmallLabel htmlFor="currentFileURL">
              {t("changeEditorDialog.currentEditorURL")}
            </SmallLabel>
            <OutlinedTextInput
              id="currentFileURL"
              type="text"
              fullWidth
              value={isCurrentURLUpdating ? t("loading") : currentFileURL}
              readOnly
              endAdornment={
                <InputAdornment
                  position="end"
                  sx={{
                    display: "flex",
                    gap: 1
                  }}
                >
                  <KudoButton
                    onClick={produceCurrentURL}
                    sx={{ height: "34px" }}
                    disabled={isCurrentURLUpdating}
                  >
                    {t("changeEditorDialog.generate")}
                  </KudoButton>
                  <KudoButton
                    onClick={handleCopy}
                    sx={{ height: "34px" }}
                    disabled={isCurrentURLUpdating}
                  >
                    {t("changeEditorDialog.copy")}
                  </KudoButton>
                </InputAdornment>
              }
            />
          </FormGroup>
          <FormGroup>
            <KudoCheckbox
              {...register("alwaysEdit")}
              label={t("changeEditorDialog.alwaysEdit")}
            />
          </FormGroup>
          <Alert severity="info">
            {t("changeEditorDialog.specifyAdditionalParametersBelow")}
            {t("changeEditorDialog.conflictsWillOverrideDefaults")}
            {t("changeEditorDialog.exampleSessionIdWillOverrite")}
          </Alert>
          <FormGroup>
            <SmallLabel htmlFor="additionalJSON">
              {t("changeEditorDialog.additionalParameters")}
            </SmallLabel>
            <TextField
              {...register("additionalParameters")}
              multiline
              rows={4}
            />
          </FormGroup>
        </Box>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          type="submit"
          sx={{
            height: "36px",
            minWidth: "64px"
          }}
        >
          {t("apply")}
        </KudoButton>
      </DialogFooter>
    </form>
  );
}
