import React, { useEffect } from "react";
import _ from "underscore";
import styled from "@material-ui/core/styles/styled";
import Box from "@material-ui/core/Box";
import ApplicationActions from "../../../actions/ApplicationActions";
import UserInfoActions from "../../../actions/UserInfoActions";
import ApplicationStore from "../../../stores/ApplicationStore";
import UserInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import Storage from "../../../utils/Storage";
import Tracker, { AK_GA } from "../../../utils/Tracker";
import TopBlock from "./TopBlock";
import BottomBlock from "./BottomBlock";
import ToolbarSpacer from "../../ToolbarSpacer";
import Footer from "../../Footer/Footer";

const TrialContainer = styled(Box)(({ theme }) => ({
  // @ts-ignore
  color: theme.palette.REY,
  height: "100vh",
  overflowY: "auto"
}));

const Main = styled("main")(({ theme }) => ({
  // @ts-ignore
  backgroundColor: theme.palette.VADER,
  flexGrow: 1
}));

export default function TrialPage() {
  const redirectUserToKudo = () => {
    Tracker.sendGAEvent("Goto", "ARES Kudo");
    const storagesInfo = UserInfoStore.getStoragesInfo();
    const areStoragesConnected = _.flatten(_.toArray(storagesInfo)).length;
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    if (MainFunctions.QueryString("bredirect")) {
      ApplicationActions.changePage(
        `${UIPrefix}${atob(
          MainFunctions.atobForURLEncoding(
            MainFunctions.QueryString("bredirect") as string
          )
        )}`
      );
    } else if (!areStoragesConnected) {
      ApplicationActions.changePage(`${UIPrefix}storages`);
    } else if (
      ((MainFunctions.QueryString("redirect") as string) || "").length >= 4 &&
      MainFunctions.QueryString("redirect") !== "trial"
    ) {
      ApplicationActions.changePage(
        `${UIPrefix}${MainFunctions.QueryString("redirect")}`
      );
    } else {
      ApplicationActions.changePage(`${UIPrefix}files`);
    }
  };

  const clearStoredValues = () => {
    Storage.deleteValue("name");
    Storage.deleteValue("surname");
  };

  const onUserUpdate = () => {
    const isIndependentLogin =
      ApplicationStore.getApplicationSetting(
        "featuresEnabled"
      ).independentLogin;
    const hasTrialEnded = UserInfoStore.hasTrialEnded();
    const isTrialPageShown = UserInfoStore.getUserInfo("isTrialShown");
    if (
      isIndependentLogin === true ||
      (UserInfoStore.getUserInfo("isLoggedIn") &&
        UserInfoStore.getUserInfo("licenseType") !== "TRIAL") ||
      hasTrialEnded === true ||
      isTrialPageShown === true
    ) {
      redirectUserToKudo();
    }
  };

  useEffect(() => {
    document.title = ApplicationStore.getApplicationSetting("defaultTitle");

    const isIndependentLogin =
      ApplicationStore.getApplicationSetting(
        "featuresEnabled"
      ).independentLogin;
    if (
      isIndependentLogin === true ||
      (UserInfoStore.getUserInfo("isLoggedIn") &&
        UserInfoStore.getUserInfo("licenseType") !== "TRIAL")
    ) {
      redirectUserToKudo();
    }
    if (UserInfoStore.getUserInfo("licenseType") === "TRIAL") {
      const hasTrialEnded = UserInfoStore.hasTrialEnded();
      const isTrialPageShown = UserInfoStore.getUserInfo("isTrialShown");
      if (hasTrialEnded || isTrialPageShown) {
        redirectUserToKudo();
      } else {
        const restDays = UserInfoStore.getDaysLeftAmount();

        Tracker.sendGAEvent(
          AK_GA.category,
          AK_GA.actions.trial,
          hasTrialEnded ? "ended" : "active",
          `${restDays}`
        );
      }
    }
    UserInfoStore.addChangeListener(INFO_UPDATE, onUserUpdate);
    return () => {
      clearStoredValues();
      UserInfoStore.removeChangeListener(INFO_UPDATE, onUserUpdate);
      UserInfoActions.modifyUserInfo({ isTrialShown: true }, true, true);
    };
  }, []);

  const hasTrialEnded = UserInfoStore.hasTrialEnded();
  return (
    <Main>
      <ToolbarSpacer />
      <TrialContainer component="div">
        <TopBlock />
        <BottomBlock
          hasTrialEnded={hasTrialEnded}
          redirectUserToKudo={redirectUserToKudo}
        />
        <Footer isIndexPage />
      </TrialContainer>
    </Main>
  );
}
