import {
  Autocomplete,
  Grid,
  TextField,
  autocompleteClasses,
  formLabelClasses,
  inputBaseClasses,
  inputClasses
} from "@mui/material";
import React from "react";
import StylingConstants from "../../../constants/appConstants/StylingConstants";
import Requests from "../../../utils/Requests";
import HelperTextComponent from "../KudoInput/HelperTextComponent";

type PropType = {
  endAdornment?: React.ReactNode;
  fileId: string;
  fullWidth?: boolean;
  helpMessageFun: (toCheck: string) => { id: string } | null;
  id?: string;
  inputDataComponent?: string;
  label?: string;
  name?: string;
  placeholder?: string;
  scope?: string;
  onValueSelected?: (value: string | null) => void;
  validationFunction: (toCheck: string) => boolean;
};

type Option = {
  email: string;
  id: string | null;
  name: string;
  surname: string;
  username: string;
};

export type RefType = {
  reset: () => void;
  getValue: () => Option | string;
};

export default React.forwardRef<RefType, PropType>(
  (
    {
      endAdornment,
      fileId,
      fullWidth,
      helpMessageFun,
      id,
      inputDataComponent,
      label,
      name,
      placeholder,
      scope,
      onValueSelected,
      validationFunction
    },
    ref
  ) => {
    const [open, setOpen] = React.useState(false);
    const [inputValue, setInputValue] = React.useState("");
    const [value, setValue] = React.useState<Option | string>("");
    const [options, setOptions] = React.useState<Option[]>([]);
    const [isLoading, setLoading] = React.useState(false);
    const [isValid, setValid] = React.useState(false);
    const [helpMessage, setHelpMessage] = React.useState<{ id: string } | null>(
      null
    );

    React.useEffect(() => {
      const toCheck = (value as Option)?.email ?? inputValue;
      if (toCheck) {
        if (validationFunction) {
          setValid(validationFunction(toCheck));
        }
        if (helpMessageFun) {
          setHelpMessage(helpMessageFun(toCheck));
        }
      }
    }, [value, inputValue]);

    React.useEffect(() => {
      let active = true;

      if (inputValue === "") {
        setValue("");
      } else if (!inputValue.includes((value as Option)?.email)) {
        // try to fetch from what is in input
        const emailRegex = /<(.*)>/g;
        const matches = inputValue.match(emailRegex);
        let newEmail = null;
        if (matches) {
          [newEmail] = matches;
        }
        if (newEmail?.length ?? -1 > 0) {
          setValue({
            ...(value as Option),
            id: null,
            email: (newEmail as string).replace(">", "").replace("<", "")
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
        const headers: Record<string, unknown> =
          Requests.getDefaultUserHeaders();
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

    React.useEffect(() => {
      onValueSelected?.(
        isValid ? (value as Option)?.email ?? inputValue : null
      );
    }, [isValid, value, inputValue]);

    const reset = () => {
      setInputValue("");
    };

    const getValue = () => value;

    React.useImperativeHandle(ref, () => ({
      reset,
      getValue
    }));

    return (
      <Grid container>
        <Grid
          xs={12}
          item
          sx={{
            [`& .${autocompleteClasses.option}`]: {
              fontSize: theme => theme.typography.pxToRem(10)
            },
            [`& .${autocompleteClasses.paper}`]: {
              fontSize: theme => theme.typography.pxToRem(10)
            },
            [`& .${autocompleteClasses.popper}`]: {
              fontSize: theme => theme.typography.pxToRem(10)
            }
          }}
        >
          <Autocomplete
            disabledItemsFocusable
            open={open}
            onOpen={() => {
              setOpen(true);
            }}
            onClose={() => {
              setOpen(false);
            }}
            disableClearable
            onChange={(_, newValue) => {
              setValue(newValue);
            }}
            getOptionLabel={(option: Option & { inputValue: string }) => {
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
            filterOptions={x => x}
            options={options}
            autoComplete={false}
            value={value}
            freeSolo
            onInputChange={(_, newInputValue) => {
              setInputValue(newInputValue);
            }}
            loading={isLoading}
            fullWidth={fullWidth}
            sx={{
              [`& .${autocompleteClasses.input}`]: {
                fontSize: theme => theme.typography.pxToRem(12)
              }
            }}
            renderInput={params => {
              if (!value) params.inputProps.value = inputValue;

              return (
                <TextField
                  {...params}
                  variant="standard"
                  id={id}
                  name={name}
                  autoFocus
                  data-component={inputDataComponent}
                  placeholder={placeholder}
                  autoComplete="off"
                  label={label}
                  className="kudoInput"
                  InputProps={{
                    ...params.InputProps,
                    endAdornment
                  }}
                  sx={{
                    [`& .${formLabelClasses.root}`]: {
                      position: "relative",
                      color: theme => theme.palette.OBI,
                      transform: "none",
                      marginBottom: theme => theme.spacing(1),
                      display: "block",
                      lineHeight: 1,
                      fontSize: theme => theme.typography.pxToRem(12)
                    },
                    width: "100%",
                    fontSize: theme => theme.typography.pxToRem(14),
                    [`& .${inputBaseClasses.root}`]: {
                      width: "100%",
                      padding: theme => `0 ${theme.typography.pxToRem(8)}`,
                      height: theme => theme.typography.pxToRem(35),
                      fontFamily: theme => theme.typography.fontFamily,
                      border: theme =>
                        `${theme.typography.pxToRem(1)} solid ${
                          theme.palette.REY
                        }`,
                      color: ({ palette }) => palette.BLACK,
                      backgroundColor: theme => theme.palette.LIGHT,
                      transition: theme =>
                        theme.transitions.create(["border-color"]),
                      boxShadow: "none",
                      borderRadius: theme => theme.spacing(0.5),
                      boxSizing: "border-box",
                      userSelect: "text",
                      marginTop: 0,
                      "&[disabled]": {
                        color: theme => theme.palette.REY
                      }
                    },
                    [`& .${inputBaseClasses.input}::placeholder`]: {
                      color: theme => theme.palette.VADER
                    },
                    [`& .${inputBaseClasses.root}:hover, & .${inputBaseClasses.root}:focus, & .${inputBaseClasses.root}:active`]:
                      {
                        color: theme => `${theme.palette.OBI}`,
                        borderColor: theme => `${theme.palette.OBI}`,
                        transition: theme =>
                          theme.transitions.create(["border-color"])
                      },
                    [`& .${inputClasses.underline}:before, & .${inputClasses.underline}:after, & .${inputClasses.underline}:hover:not(.Mui-disabled):before`]:
                      {
                        border: "none"
                      },
                    [`& .${inputClasses.underline}:before:hover, & .${inputClasses.underline}:before:focus`]:
                      {
                        border: "none"
                      }
                  }}
                />
              );
            }}
          />
        </Grid>
        <Grid xs={12} item>
          <HelperTextComponent
            helpText={helpMessage}
            validationState={
              isValid ? StylingConstants.SUCCESS : StylingConstants.ERROR
            }
          />
        </Grid>
      </Grid>
    );
  }
);
