/* eslint-disable react/prop-types */
import React from "react";
import Autocomplete from "@material-ui/lab/Autocomplete";
import { makeStyles } from "@material-ui/core";
import clsx from "clsx";
import TextField from "@material-ui/core/TextField";
import Requests from "../../../utils/Requests";
import FormManagerActions from "../../../actions/FormManagerActions";
import HelperTextComponent from "../KudoInput/HelperTextComponent";
import StylingConstants from "../../../constants/appConstants/StylingConstants";
import FormManagerStore, {
  CLEAR_EVENT
} from "../../../stores/FormManagerStore";

const useStyles = makeStyles(theme => ({
  root: {
    width: "100%",
    "& .MuiInputBase-input": {
      width: "100%",
      padding: `${theme.typography.pxToRem(10)} ${theme.typography.pxToRem(8)}`,
      height: theme.typography.pxToRem(36),
      fontFamily: theme.typography.fontFamily,
      border: `${theme.typography.pxToRem(1)} solid ${theme.palette.REY}`,
      color: theme.palette.CLONE,
      backgroundColor: theme.palette.LIGHT,
      transition: theme.transitions.create(["border-color"]),
      boxShadow: "none",
      borderRadius: 0,
      boxSizing: "border-box",
      userSelect: "text",

      "&[disabled]": {
        color: theme.palette.REY
      }
    },
    "& .MuiInputBase-input::placeholder": {
      color: theme.palette.VADER
    },
    "& .MuiInputBase-input:hover, & .MuiInputBase-input:focus, & .MuiInputBase-input:active":
      {
        color: `${theme.palette.OBI}!important`,
        borderColor: `${theme.palette.OBI}!important`,
        transition: theme.transitions.create(["border-color"])
      },
    "& .MuiInput-underline:before, & .MuiInput-underline:after, & .MuiInput-underline:hover:not(.Mui-disabled):before":
      {
        border: "none"
      },
    "& .MuiInput-underline:before:hover, & .MuiInput-underline:before:focus": {
      border: "none"
    }
  },
  error: {
    "& .MuiInputBase-input": {
      border: `1px solid ${theme.palette.KYLO}`,
      color: theme.palette.KYLO
    },
    "& .MuiInputBase-input:hover, & .MuiInputBase-input:focus, & .MuiInputBase-input:active":
      {
        color: `${theme.palette.KYLO}!important`,
        borderColor: `${theme.palette.KYLO}!important`
      }
  },
  label: {
    position: "relative",
    color: `${theme.palette.OBI} !important`,
    transform: "none",
    marginBottom: theme.spacing(1),
    display: "block",
    lineHeight: 1
  },
  input: {
    marginTop: "0 !important"
  }
}));

export default function KudoAutocomplete({
  formId,
  id,
  name,
  label,
  inputDataComponent,
  validationFunction,
  helpMessageFun,
  fileId,
  scope
}) {
  const innerClasses = useStyles();
  const resultClass = clsx(innerClasses.root, "kudoInput");
  const [open, setOpen] = React.useState(false);
  const [inputValue, setInputValue] = React.useState("");
  const [value, setValue] = React.useState(null);
  const [options, setOptions] = React.useState([]);
  const [isLoading, setLoading] = React.useState(false);
  const [isValid, setValid] = React.useState(false);
  const [helpMessage, setHelpMessage] = React.useState(null);

  React.useEffect(() => {
    const toCheck = value && value.email ? value.email : inputValue;
    if (toCheck !== null) {
      if (validationFunction) {
        setValid(validationFunction(toCheck));
      }
      if (helpMessageFun) {
        setHelpMessage(helpMessageFun(toCheck));
      }
    }
  }, [value, inputValue]);

  React.useEffect(() => {
    FormManagerStore.registerFormElement(formId, "INPUT", id, name, "", false);
  }, []);

  React.useEffect(() => {
    FormManagerActions.changeInputValue(
      formId,
      id,
      value && value.email ? value?.email : inputValue,
      isValid,
      false
    );
  }, [inputValue, value, isValid]);

  React.useEffect(() => {
    let active = true;

    if (inputValue === "") {
      setValue(null);
    } else if (value && value.email && !inputValue.includes(value.email)) {
      // try to fetch from what is in input
      const emailRegex = /<(.*)>/g;
      const matches = inputValue.match(emailRegex);
      let newEmail = null;
      if (matches) {
        [newEmail] = matches;
      }
      if (newEmail && newEmail.length > 0) {
        setValue({
          ...value,
          id: null,
          email: newEmail.replace(">", "").replace("<", "")
        });
      } else {
        setValue(inputValue);
      }
    }
    if (inputValue === "" && !open) {
      return undefined;
    }
    setLoading(true);

    (async () => {
      const headers = Requests.getDefaultUserHeaders();
      headers.pattern = encodeURIComponent(inputValue);
      headers.fileId = fileId;
      if (scope) {
        headers.scope = scope;
      }
      const response = await Requests.sendGenericRequest(
        `/users/share/email`,
        "GET",
        headers
      );

      if (active) {
        setOptions(response.data.results);
        setLoading(false);
      }
    })();

    return () => {
      active = false;
    };
  }, [value, inputValue]);

  const clearValue = () => {
    setTimeout(() => {
      setInputValue("");
    }, 0);
  };

  React.useEffect(() => {
    FormManagerStore.on(CLEAR_EVENT + formId, clearValue);
    return () => {
      FormManagerStore.removeListener(CLEAR_EVENT + formId, clearValue);
    };
  }, []);

  return (
    <>
      <Autocomplete
        open={open}
        onOpen={() => {
          setOpen(true);
        }}
        onClose={() => {
          setOpen(false);
        }}
        disableClearable
        onChange={(event, newValue) => {
          setValue(newValue);
        }}
        getOptionLabel={option => {
          // Value selected with enter, right from the input
          if (typeof option === "string") {
            return option;
          }
          // Add "xxx" option created dynamically
          if (option.inputValue) {
            return option.inputValue;
          }
          // Regular option
          return `${option.username} <${option.email}>`;
        }}
        renderOption={option => `${option.username} <${option.email}>`}
        filterOptions={x => x}
        options={options}
        autoComplete={false}
        value={value}
        freeSolo
        onInputChange={(event, newInputValue) => {
          setInputValue(newInputValue);
        }}
        loading={isLoading}
        renderInput={params => {
          if (!value) params.inputProps.value = inputValue;

          return (
            <TextField
              // eslint-disable-next-line react/jsx-props-no-spreading
              {...params}
              id={id}
              name={name}
              autoFocus
              data-component={inputDataComponent}
              className={resultClass}
              autoComplete="off"
              label={label}
              InputLabelProps={{ className: innerClasses.label }}
              InputProps={{
                ...params.InputProps,
                className: innerClasses.input
              }}
            />
          );
        }}
      />
      <HelperTextComponent
        showHelpBlock={helpMessage !== null}
        helpText={helpMessage}
        validationState={
          isValid ? StylingConstants.SUCCESS : StylingConstants.ERROR
        }
      />
    </>
  );
}
