import {
  FormControl,
  Input,
  InputAdornment,
  typographyClasses
} from "@mui/material";
import React, {
  ChangeEvent,
  RefCallback,
  useCallback,
  useMemo,
  useRef,
  useState
} from "react";
import _ from "underscore";
import FilesListActions from "../../../../actions/FilesListActions";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListStore from "../../../../stores/FilesListStore";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";

type PropType = {
  id: string;
  initialValue: string;
  onFinish?: () => void;
};

export default function RenameField({
  id,
  initialValue,
  onFinish = () => null
}: PropType) {
  const extension = useMemo(
    () => MainFunctions.getExtensionFromName(initialValue),
    [initialValue]
  );
  const pureName = useMemo(
    () =>
      initialValue.substring(
        0,
        initialValue.lastIndexOf(`.${extension}`) + 1
          ? initialValue.lastIndexOf(`.${extension}`)
          : initialValue.length
      ),
    [initialValue, extension]
  );
  const [value, setValue] = useState(pureName);
  const valueRef = useRef(value);
  const handleValueChange = (event: ChangeEvent<HTMLInputElement>) => {
    setValue(event.target.value);
    valueRef.current = event.target.value;
  };

  const saveData = () => {
    const newName = `${valueRef.current}.${extension}`;
    if (newName !== initialValue && valueRef.current.trim() !== "") {
      FilesListActions.saveFile(
        _.extend(FilesListStore.getCurrentFile(), {
          name: newName,
          filename: newName
        })
      );
      FilesListActions.updateName(id, "file", newName)
        .then(data => {
          if (data.name && data.name !== newName) {
            SnackbarUtils.alertWarning({ id: "duplicateNameAutoRename" });

            FilesListActions.saveFile(
              _.extend(FilesListStore.getCurrentFile(), {
                name: data.name,
                filename: data.name
              })
            );
          }
        })
        .catch(err => {
          SnackbarUtils.alertError(err.text || err.message);
          FilesListActions.saveFile(
            _.extend(FilesListStore.getCurrentFile(), {
              name: initialValue,
              filename: initialValue
            })
          );
        });
    } else if (valueRef.current.trim() === "") {
      SnackbarUtils.alertError({ id: "emptyName" });
    }
  };

  const handleKeys = (e: KeyboardEvent) => {
    switch (e.key) {
      case "Enter":
        e.preventDefault();
        e.stopPropagation();
        saveData();
        onFinish();
        break;
      case "Escape":
        e.preventDefault();
        e.stopPropagation();
        onFinish();
        break;
      default:
        break;
    }
  };

  const inputRef = useCallback<RefCallback<HTMLInputElement>>(node => {
    if (node !== null) {
      const inputs = node.getElementsByTagName("input");
      if (inputs !== null && inputs.length === 1) {
        const strLength = value.length;
        const inputField = inputs[0];
        inputField.onkeydown = handleKeys;
        if (inputField.setSelectionRange !== undefined) {
          inputField.setSelectionRange(0, strLength);
        }
      }
    }
  }, []);

  const handleBlur = () => {
    saveData();
    onFinish();
  };
  return (
    <FormControl sx={{ verticalAlign: "middle", fontSize: 10 }}>
      <Input
        ref={inputRef}
        type="text"
        value={value}
        autoFocus
        sx={{
          fontSize: 12,
          backgroundColor: theme => theme.palette.RENAME_FIELD_BACKGROUND,
          borderColor: theme => theme.palette.RENAME_FIELD_BACKGROUND,
          height: "36px",
          borderRadius: "2px",
          "& > input": {
            color: theme => theme.palette.LIGHT,
            padding: "0px 7px"
          },
          "&:before,&:after": {
            display: "none"
          }
        }}
        onChange={handleValueChange}
        onBlur={handleBlur}
        endAdornment={
          <InputAdornment
            position="end"
            sx={{
              borderRadius: 0,
              backgroundColor: theme => theme.palette.RENAME_FIELD_BACKGROUND,
              borderLeft: theme => `solid 1px ${theme.palette.DARK}`,
              color: theme => theme.palette.LIGHT,
              maxHeight: "none",
              height: "36px",
              padding: "6px 12px",
              [`& .${typographyClasses.root}`]: {
                fontSize: 12,
                color: theme => `${theme.palette.LIGHT}b3`
              }
            }}
          >{`.${extension}`}</InputAdornment>
        }
        data-component="rename-input"
      />
    </FormControl>
  );
}
