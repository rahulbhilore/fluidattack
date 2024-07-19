import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import { Grid, InputAdornment, makeStyles, Input } from "@material-ui/core";
import { styled } from "@material-ui/core/styles";
import Checkbox from "@material-ui/core/Checkbox";
import { FormattedMessage } from "react-intl";
import Tooltip from "@material-ui/core/Tooltip";
import clsx from "clsx";
import FormManagerStore, { CHECKBOX } from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";
import UserInfoStore from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import iconsDictionary from "../../../constants/appConstants/ObjectIcons";
import useStateRef from "../../../utils/useStateRef";

const StyledImg = styled("img")(({ theme }) => ({
  width: "18px",
  verticalAlign: "middle",
  marginLeft: theme.spacing(1),
  marginRight: theme.spacing(1)
}));

const StyledCheckbox = styled(Checkbox)(({ theme }) => ({
  marginLeft: "0",
  "& .MuiSvgIcon-root": {
    width: "25px",
    height: "25px"
  },
  "&.MuiCheckbox-root": {
    color: theme.palette.CLONE
  },
  "&.MuiCheckbox-colorPrimary.Mui-checked": {
    color: theme.palette.OBI
  }
}));

const StyledInput = styled(Input)(({ theme }) => ({
  width: "100%",
  "& .MuiInputBase-input": {
    color: theme.palette.DARK,
    transitionDuration: ".25s",
    "&[disabled]": {
      color: theme.palette.CLONE
    }
  },
  "& .MuiOutlinedInput-notchedOutline, &:hover .MuiOutlinedInput-notchedOutline":
    {
      borderColor: theme.palette.DARK
    },
  "& .MuiOutlinedInput-inputMarginDense": {
    paddingTop: "13px",
    paddingBottom: "10px"
  },
  "& .MuiOutlinedInput-root.Mui-disabled .MuiOutlinedInput-notchedOutline": {
    borderColor: theme.palette.CLONE
  }
}));

const RECHECK_EVENT = "recheckDuplicates";

const useStyles = makeStyles(theme => ({
  root: {
    verticalAlign: "middle",
    width: `calc(100% - ${theme.spacing(1)}px)`
  },
  inputField: {
    backgroundColor: theme.palette.WHITE,
    border: `1px solid ${theme.palette.DARK}`,
    height: "36px",
    borderRadius: "4px",
    "&.addon": {
      borderRight: "none"
    },
    "& > input": {
      color: theme.palette.JANGO,
      fontSize: theme.typography.pxToRem(12),
      padding: theme.spacing(0, 1)
    },
    "&:before,&:after": {
      display: "none"
    },
    "&.Mui-error": {
      border: "1px solid red",
      "&.addon": {
        borderRight: "none"
      },
      "& > .MuiInputAdornment-positionEnd": {
        borderLeft: `1px solid red`
      }
    }
  },
  addonGroup: {
    borderRadius: 0,
    backgroundColor: theme.palette.REY,
    borderLeft: `solid 1px ${theme.palette.DARK}`,
    maxHeight: "none",
    height: "36px",
    padding: theme.spacing(1, 2),
    "& > p": {
      color: theme.palette.JANGO
    }
  }
}));

export default function SingleDuplicate({
  id,
  defaultName,
  type,
  parent,
  mimeType,
  formId,
  updateEntitiesCache,
  removeFromCache,
  checkCurrentDuplicates,
  parentInfo
}) {
  const classes = useStyles();
  const initialValid = parentInfo[defaultName];
  const [isValid, setValid] = useState(initialValid);

  const { name: initName, extension } =
    MainFunctions.parseObjectName(defaultName);

  const [nameState, setNameState, nameRefState] = useStateRef(initName);
  const [doRestoreState, setRestoreState, doRestoreRefState] =
    useStateRef(true);

  FormManagerStore.registerFormElement(
    formId,
    CHECKBOX,
    id,
    id,
    {
      oldName: defaultName,
      name: nameState,
      type,
      cancelFlag: !doRestoreState,
      parentId: parent,
      doRestore: doRestoreState,
      id
    },
    isValid
  );

  const toggleCancel = () => {
    if (doRestoreState) setRestoreState(false);
    else {
      const anyToRestore = Object.values(
        FormManagerStore.getAllFormElementsData("restoreDuplicatesForm")
      ).some(o => o.value?.doRestore === true);

      if (!anyToRestore) {
        const submitButtonId = FormManagerStore.getButtonIdForForm(
          "restoreDuplicatesForm"
        );
        FormManagerActions.changeButtonState(
          "restoreDuplicatesForm",
          submitButtonId,
          true
        );
      }

      setRestoreState(true);
    }
  };

  useEffect(() => {
    if (doRestoreState) {
      updateEntitiesCache(parent, type, id, nameState, nameState);
      setValid(checkCurrentDuplicates(parent, type, id, nameState));
    } else {
      setValid(true);
      removeFromCache(parent, type, id, nameState, true);
    }
  }, [doRestoreState]);

  const changeName = event => {
    if (doRestoreState) {
      const formattedName = event.target.value || "";
      updateEntitiesCache(
        parent,
        type,
        id,
        formattedName,
        type === "file" && extension.length > 0
          ? `${nameState}.${extension}`
          : nameState
      );
      setNameState(formattedName);
      setValid(checkCurrentDuplicates(parent, type, id, formattedName));
    }
  };

  /**
   * @description Save changed value to store
   * @function
   * @private
   */
  const emitChangeAction = () => {
    if (
      formId.length > 0 &&
      FormManagerStore.checkIfElementIsRegistered(formId, id)
    ) {
      FormManagerActions.changeInputValue(
        formId,
        id,
        {
          oldName: defaultName,
          name: nameState,
          type,
          cancelFlag: !doRestoreState,
          parentId: parent,
          doRestore: doRestoreState,
          id
        },
        isValid,
        false
      );
    }
  };

  useEffect(emitChangeAction, [isValid, nameState, doRestoreState]);

  const svgType = UserInfoStore.getIconClassName(
    MainFunctions.getExtensionFromName(defaultName),
    type,
    nameState,
    mimeType
  );
  // just in case, but we should
  // always be able to get from the dictionary.
  let svgLink = `images/icons/${svgType}.svg`;
  if (Object.prototype.hasOwnProperty.call(iconsDictionary, `${svgType}SVG`)) {
    svgLink = iconsDictionary[`${svgType}SVG`];
  }
  const checkDuplicates = () => {
    if (doRestoreRefState.current)
      setValid(checkCurrentDuplicates(parent, type, id, nameRefState.current));
  };

  useEffect(() => {
    document.addEventListener(RECHECK_EVENT, checkDuplicates);
    return () => {
      document.removeEventListener(RECHECK_EVENT, checkDuplicates);
    };
  }, []);

  return (
    <Grid container spacing={2}>
      <Grid item xs={3} sm={2}>
        <Tooltip
          placement="top"
          title={
            <FormattedMessage
              id={doRestoreState ? "restoreThis" : "cancelRestore"}
            />
          }
        >
          <StyledCheckbox
            color="primary"
            onChange={toggleCancel}
            checked={doRestoreState}
          />
        </Tooltip>
        <StyledImg src={svgLink} alt={nameState} />
      </Grid>
      <Grid item xs={9} sm={10}>
        <StyledInput
          size="small"
          error={!isValid}
          variant="outlined"
          type="text"
          onChange={changeName}
          className={clsx(
            classes.inputField,
            type === "file" && extension.length > 0 ? "addon" : ""
          )}
          disabled={doRestoreState === false}
          value={nameState}
          endAdornment={
            type === "file" &&
            extension.length > 0 && (
              <InputAdornment
                position="end"
                className={classes.addonGroup}
              >{`.${extension}`}</InputAdornment>
            )
          }
        />
      </Grid>
    </Grid>
  );
}

SingleDuplicate.propTypes = {
  id: PropTypes.string.isRequired,
  defaultName: PropTypes.string.isRequired,
  type: PropTypes.string.isRequired,
  parent: PropTypes.string.isRequired,
  mimeType: PropTypes.string,
  parentInfo: PropTypes.shape({
    [PropTypes.string]: PropTypes.arrayOf(PropTypes.string)
  }).isRequired,
  updateEntitiesCache: PropTypes.func.isRequired,
  removeFromCache: PropTypes.func.isRequired,
  checkCurrentDuplicates: PropTypes.func.isRequired,
  formId: PropTypes.string
};

SingleDuplicate.defaultProps = {
  formId: "restoreDuplicatesForm",
  mimeType: ""
};
