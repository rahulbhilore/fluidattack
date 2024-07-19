import React from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import InputLabel from "@mui/material/InputLabel";
import MenuItem from "@mui/material/MenuItem";
import ListSubheader from "@mui/material/ListSubheader";
import FormControl from "@mui/material/FormControl";
import Select from "@mui/material/Select";
import InputBase from "@material-ui/core/InputBase";
import { FormattedMessage } from "react-intl";

import UserInfoStore from "../../../../stores/UserInfoStore";
import ApplicationStore from "../../../../stores/ApplicationStore";
import MainFunctions from "../../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  listSubheader: {
    backgroundColor: `rgb(225, 225, 225)!important`,
    color: `${theme.palette.GLOBAL_BACKGROUND}!important`,
    fontSize: "14px!important",
    lineHeight: "30px!important",
    paddingLeft: "8px"
  },
  menuItem: {
    fontSize: "14px!important",
    lineHeight: "30px!important",
    height: "30px",
    "&.Mui-selected": {
      backgroundColor: `${theme.palette.OBI}!important`,
      color: `${theme.palette.LIGHT}!important`
    }
  },
  formControl: {
    width: "100%",
    mt: 0,
    mb: 1,
    "& .MuiInputBase-root": {
      marginTop: 0
    },
    "& .MuiInputBase-input": {
      borderRadius: 0,
      width: "100%",
      position: "relative",
      border: "1px solid #ced4da",
      height: "20px",
      fontFamily:
        "Open Sans, Tahoma, Geneva, MS Gothic, PingFang SC, Heiti SC, sans-serif;",
      color: `${theme.palette.DARK}`,
      paddingTop: "10px",
      paddingBottom: "5px",
      paddingLeft: "12px"
    },
    "& .MuiInputLabel-root": {
      position: "relative",
      color: `${theme.palette.OBI}`,
      marginBottom: "8px",
      transform: "none",
      fontFamily:
        "Open Sans, Tahoma, Geneva, MS Gothic, PingFang SC, Heiti SC, sans-serif;",
      fontSize: "12px",
      fontWeight: 400
    }
  }
}));

export default function StoragesSelector({ onSelect, currentStorageInfo }) {
  const classes = useStyles();
  const { storageId: defaultStorageId } = currentStorageInfo;

  const storagesInfo = UserInfoStore.getStoragesInfo();

  const selectItems = [];

  const findStorageFullName = shortName => {
    const storagesSettings =
      ApplicationStore.getApplicationSetting("storagesSettings");

    const item = storagesSettings.find(storage => storage.name === shortName);

    if (item) return item.displayName;
    return shortName;
  };

  const selectRenderValue = value => {
    let userName = null;
    let fullStorageName = null;

    Object.keys(storagesInfo)
      .filter(key => storagesInfo[key].length)
      .forEach(filteredKey => {
        const foundStorage = storagesInfo[filteredKey].find(
          item => item[`${filteredKey}_id`] === value
        );

        if (!foundStorage) return;

        userName = foundStorage[`${filteredKey}_username`];
        fullStorageName = findStorageFullName(filteredKey);
      });

    return `${fullStorageName} - ${userName}`;
  };

  const handleOnSelect = e => {
    let newFindStorage = null;
    let newStorageKey = null;
    let newStorageId = null;

    Object.keys(storagesInfo)
      .filter(key => storagesInfo[key].length)
      // eslint-disable-next-line arrow-body-style
      .forEach(filteredKey => {
        if (newFindStorage) return;
        newFindStorage = storagesInfo[filteredKey].find(
          item => item[`${filteredKey}_id`] === e.target.value
        );

        if (newFindStorage) {
          newStorageKey = filteredKey;
          newStorageId = newFindStorage[`${filteredKey}_id`];
        }
      });

    if (!newFindStorage) {
      onSelect(currentStorageInfo);
      return;
    }

    onSelect({
      storageType: MainFunctions.serviceNameToStorageCode(newStorageKey),
      storageId: newStorageId
    });
  };

  Object.keys(storagesInfo)
    .filter(key => storagesInfo[key].length)
    .forEach(filteredKey => {
      selectItems.push(
        <ListSubheader className={classes.listSubheader}>
          {findStorageFullName(filteredKey)}:
        </ListSubheader>
      );
      storagesInfo[filteredKey].forEach(item => {
        selectItems.push(
          <MenuItem
            className={classes.menuItem}
            value={item[`${filteredKey}_id`]}
            data-component="storage-option-item"
            data-account={`${filteredKey}_${item[`${filteredKey}_username`]}`}
            role="option"
          >
            {item[`${filteredKey}_username`]}
          </MenuItem>
        );
      });
    });

  return (
    <FormControl className={classes.formControl}>
      <InputLabel htmlFor="select-storage">
        <FormattedMessage id="selectStorage" />
      </InputLabel>
      <Select
        defaultValue={defaultStorageId}
        id="select-storage"
        data-component="select-storage"
        input={<InputBase />}
        onChange={handleOnSelect}
        renderValue={selectRenderValue}
      >
        {selectItems}
      </Select>
    </FormControl>
  );
}

StoragesSelector.propTypes = {
  onSelect: PropTypes.func.isRequired,
  currentStorageInfo: PropTypes.bool.isRequired
};
