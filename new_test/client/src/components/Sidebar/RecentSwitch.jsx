import React, { useCallback, useEffect, useMemo, useState } from "react";
import PropTypes from "prop-types";
import { makeStyles } from "@material-ui/core/styles";
import ListItem from "@material-ui/core/ListItem";
import KudoSwitch from "../Inputs/KudoSwitch/KudoSwitch";
import UserInfoStore, {
  RECENT_FILES_SWITCH_UPDATE
} from "../../stores/UserInfoStore";
import UserInfoActions from "../../actions/UserInfoActions";

import recentFilesSVG from "../../assets/images/recent-files.svg";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.VADER,
    margin: 0,
    padding: "5px 10px",
    [theme.breakpoints.down("xs")]: {
      padding: "3px 10px"
    }
  }
}));

export default function RecentSwitch({ isMobile }) {
  const [hasStateChanged, setHasStateChanged] = useState(
    UserInfoStore.getUserInfo("isRecentFilesSwitchUpdated")
  );

  const isChecked = useMemo(() => {
    if (hasStateChanged) return UserInfoStore.getUserInfo("showRecent");
    if (isMobile) return false;
    return UserInfoStore.getUserInfo("showRecent");
  }, [hasStateChanged, isMobile]);
  const classes = useStyles();

  const onRecentSwitchStateChanged = () => {
    setHasStateChanged(UserInfoStore.getUserInfo("isRecentFilesSwitchUpdated"));
  };

  const onChange = useCallback(showRecent => {
    UserInfoActions.modifyUserInfo({ showRecent }, true);
    UserInfoActions.updateRecentFilesSwitchChangeState(true);
  }, []);

  useEffect(
    () => () => {
      UserInfoStore.addChangeListener(
        RECENT_FILES_SWITCH_UPDATE,
        onRecentSwitchStateChanged
      );

      return () => {
        UserInfoStore.removeChangeListener(
          RECENT_FILES_SWITCH_UPDATE,
          onRecentSwitchStateChanged
        );
      };
    },
    []
  );

  return (
    <ListItem className={classes.root}>
      <KudoSwitch
        id="showRecent"
        name="showRecent"
        label="recentFiles"
        iconSrc={recentFilesSVG}
        defaultChecked={isChecked}
        onChange={onChange}
        styles={{
          formGroup: {
            display: "flex",
            flexDirection: "row",
            margin: 0
          }
        }}
        dataComponent="recent-files-switch"
      />
    </ListItem>
  );
}

RecentSwitch.propTypes = {
  isMobile: PropTypes.bool.isRequired
};
