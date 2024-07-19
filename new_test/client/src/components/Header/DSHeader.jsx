import { PureComponent } from "react";
import $ from "jquery";
import { injectIntl } from "react-intl";
import PropTypes from "prop-types";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationActions from "../../actions/ApplicationActions";
import TableActions from "../../actions/TableActions";
import ApplicationStore, { UPDATE } from "../../stores/ApplicationStore";
import ModalActions from "../../actions/ModalActions";
import UserInfoActions from "../../actions/UserInfoActions";
import UserInfoStore, { INFO_UPDATE } from "../../stores/UserInfoStore";
import Logger from "../../utils/Logger";

import FilesListStore from "../../stores/FilesListStore";

/* eslint-disable */
const initialConfig = state => ({
  userName: state.userName,
  brand: "DraftSight",
  lang: "en",
  application: "",
  topMenus: ["profile", "help", "share"],
  useTagger: false,
  activateFederatedSearch: true,
  xappsMode: true,
  baseAppletPath:
    "https://eu1-215dsi0708-ifwe.3dexperience.3ds.com/resources/20171115T194511Z/en/webapps/i3DXCompass/assets/applet/",
  baseHtmlPath:
    "https://eu1-215dsi0708-ifwe.3dexperience.3ds.com/resources/20171115T194511Z/en/webapps/i3DXCompass/",
  baseImgPath:
    "https://eu1-215dsi0708-ifwe.3dexperience.3ds.com/resources/20171115T194511Z/en/webapps/i3DXCompass/assets/images/",
  baseNlsPath:
    "https://eu1-215dsi0708-ifwe.3dexperience.3ds.com/resources/20171115T194511Z/en/webapps/i3DXCompass/assets/lang/",
  myAppsBaseURL: "https://eu1-215dsi0708-apps.3dexperience.3ds.com/enovia",
  passportUrl: "https://eu1-dsi-iam.3dexperience.3ds.com:443",
  proxyTicketUrl:
    "https://eu1-215dsi0708-ifwe.3dexperience.3ds.com/api/passport/ticket?url=V6",
  showWidgetApps: true,
  tagsServerUrl: "https://eu1-215dsi0708-ifwe.3dexperience.3ds.com",
  brandAbout:
    "3DEXPERIENCE R2018x &#xa9; 2017 Dassault Syst&#xe8;mes. All rights reserved.",
  appNameAbout: "3DDashboard",
  numberAbout:
    "R420-euw1-20171115T194500Z - euw1-215dsi0708-2018x.D31.R420rel.201711152045",
  is3DDriveEnabled: "true",
  isNotificationsEnabled: "true",
  communityUrl:
    "https://dsext001-eu1-215dsi0708-3dswym.3dexperience.3ds.com/#community:1209",
  getStartedUrl: "https://eu1-215dsi0708-apps.3dexperience.3ds.com/enovia",
  userId: "vau1",
  activateInstantMessaging: false,
  startupParams: {
    success: true,
    code: 0,
    admin: false,
    pending: false,
    ko: false,
    legal: [
      {
        id: "DSEXT001",
        displayName: "DSEXT001",
        manager: false,
        cookie: {
          accepted: false,
          message: "",
          file: ""
        },
        legalInfo: "",
        tos: "",
        dp: "",
        accepted: true,
        acceptedTOS: false,
        acceptedDP: false,
        message: "",
        footerDisplay: false
      }
    ],
    cstorage: [
      {
        id: "DSEXT001",
        displayName: "DSEXT001",
        url:
          "https://DSEXT001-eu1-215dsi0708-space.3dexperience.3ds.com:443/enovia"
      }
    ],
    search(parameters) {
      alert(`Search launched with ${parameters.value}`);
    },
    hpc: [],
    swym: [
      {
        id: "DSEXT001",
        displayName: "DSEXT001",
        url: "https://DSEXT001-eu1-215dsi0708-3dswym.3dexperience.3ds.com"
      }
    ],
    tagger: [
      {
        id: "DSEXT001",
        displayName: "DSEXT001",
        url:
          "https://DSEXT001-eu1-215dsi0708-6wtag.3dexperience.3ds.com:443/enovia"
      }
    ],
    instantMessaging: [
      /*
                      {
                          "id":"DSEXT001",
                          "displayName":"DSEXT001",
                          "url":"https://admrtc1215dsi0708euw1-eu1-215dsi0708-rtc1.3dexperience.3ds.com",
                          "enabled":false
                      } */
    ],
    roles: [
      {
        platform: "DSEXT001",
        roles: [
          {
            id: "IFW",
            name: "Business Innovation",
            default: true,
            platforms: ["DSEXT001"]
          },
          {
            id: "NSW",
            name: "Watcher",
            default: false,
            platforms: ["DSEXT001"]
          },
          {
            id: "CSV",
            name: "Industry Innovation",
            default: false,
            platforms: ["DSEXT001"]
          },
          {
            id: "RAP-GKVQWGVOZ",
            name: "Developer",
            default: false,
            platforms: ["DSEXT001"]
          }
        ]
      }
    ],
    defaultRole: "IFW",
    showCoachmark: false
  },
  legal: [
    {
      id: "DSEXT001",
      displayName: "DSEXT001",
      manager: false,
      cookie: {
        accepted: false,
        message: "",
        file: ""
      },
      legalInfo: "",
      tos: "",
      dp: "",
      accepted: true,
      acceptedTOS: false,
      acceptedDP: false,
      message: "",
      footerDisplay: false
    }
  ],
  events: {
    tagger() {
      alert("Tagger Click");
    },
    search(parameters) {
      alert(`Search launched with ${parameters.value}`);
    },
    clearSearch() {
      alert("Clear Search");
    }
  },
  renderFooter: false,
  swymUrl: "https://DSEXT001-eu1-215dsi0708-3dswym.3dexperience.3ds.com"
});
/* eslint-enable */

let DSTopFrame = null;
let DSTopBar = null;
let theTopBarProxyInstance = null;
let formatMessage = null;

class DSHeader extends PureComponent {
  static loadAMDModule() {
    return new Promise(resolve => {
      if (DSTopFrame !== null) {
        resolve();
      } else {
        /* eslint-disable */
        requirejs(
          [
            "UWA/Core",
            "DS/TopFrame/TopFrame",
            "DS/TopBar/TopBar",
            "DS/TopBarProxy/TopBarProxy"
          ],
          (UWACore, TopFrame, TopBar, TopBarProxy) => {
            DSTopFrame = TopFrame;
            DSTopBar = TopBar;
            theTopBarProxyInstance = new TopBarProxy({ id: "helpMenu" });
            resolve();
          }
        );
        /* eslint-enable */
      }
    });
  }

  static initiateLogout() {
    if (
      MainFunctions.detectPageType() === "file" &&
      !FilesListStore.getCurrentFile().viewFlag
    ) {
      UserInfoActions.saveLogoutRequest(true);
    } else {
      UserInfoActions.logout();
    }
  }

  static checkShareVisibility() {
    const shareButton = document.getElementsByClassName(
      "share topbar-menu-item"
    );
    if (shareButton && shareButton[0]) {
      if (MainFunctions.detectPageType() === "file") {
        shareButton[0].style.display = "block";
        // have to do this in order to toggle "take permissions link"
        theTopBarProxyInstance.setContent(DSHeader.getMenusItems());
      } else {
        shareButton[0].style.display = "none";
      }
    } else {
      // eslint-disable-next-line no-console
      console.error("No share button exists");
    }
  }

  static propTypes = {
    changePageType: PropTypes.func,
    intl: PropTypes.shape({ formatMessage: PropTypes.func.isRequired })
      .isRequired
  };

  static defaultProps = {
    changePageType: () => null
  };

  static onFileUpdate() {
    const currentPage = MainFunctions.detectPageType();
    if (
      currentPage === "file" &&
      theTopBarProxyInstance &&
      typeof theTopBarProxyInstance.setContent === "function"
    ) {
      // have to do this in order to toggle "take permissions link"
      theTopBarProxyInstance.setContent(DSHeader.getMenusItems());
    }
  }

  constructor(props) {
    super(props);
    ({ formatMessage } = props.intl);
    this.state = {
      userName: DSHeader.getUserName() || "",
      isTopBarShown: false
    };
  }

  static getUserName() {
    const userInfo = UserInfoStore.getUserInfo();
    if (`${userInfo.name || ""}${userInfo.surname || ""}`.length > 0) {
      return `${userInfo.name} ${userInfo.surname || ""}`.trim();
    }
    return (userInfo.username || userInfo.email || " ").trim();
  }

  componentDidMount() {
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUserInfoLoaded);
    ApplicationStore.addChangeListener(UPDATE, this.onPageChanged);
    FilesListStore.addChangeListener(DSHeader.onFileUpdate);
    if (UserInfoStore.getUserInfo("isFullInfo") === true) {
      this.loadTopBar();
    }
  }

  componentDidUpdate() {
    DSHeader.loadAMDModule().then(() => {
      const { userName } = this.state;
      try {
        DSTopBar.set({
          userName
        });
      } catch (ex) {
        Logger.addEntry("WARNING", "DS specific error. Ignore this one");
      }
      this.setTopBarContent();
    });
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUserInfoLoaded);
    ApplicationStore.removeChangeListener(UPDATE, this.onPageChanged);
    FilesListStore.removeChangeListener(DSHeader.onFileUpdate);
    const topBarDOMElement = document.getElementById("topbar");
    if (topBarDOMElement) {
      topBarDOMElement.style.display = "none";
    }
  }

  onPageChanged = () => {
    if (location.pathname === "/") {
      const topBarDOMElement = document.getElementById("topbar");
      if (topBarDOMElement) {
        topBarDOMElement.style.display = "none";
      }
      // this is a hack to hide DS overlay that prevents login form usage
      $("body.DraftSight > div.main").css("display", "none");
    } else {
      DSHeader.checkShareVisibility();
      this.setState({
        isTopBarShown: false
      });
    }
  };

  onUserInfoLoaded = () => {
    if (UserInfoStore.getUserInfo("isFullInfo") === true) {
      this.setState(
        {
          userName: DSHeader.getUserName() || ""
        },
        this.loadTopBar
      );
    }
  };

  static getEditRightsControl() {
    return (
      FilesListStore.getCurrentFile().viewFlag === true &&
      UserInfoStore.getUserInfo("options").editor === true &&
      FilesListStore.getCurrentFile().editingUserId ===
        UserInfoStore.getUserInfo("id")
    );
  }

  static getMenusItems() {
    const defaultItems = {
      profile: [],
      share: [
        {
          label: formatMessage({ id: "shareThisFile" }),
          onExecute: () => {
            ModalActions.shareManagement(FilesListStore.getCurrentFile()._id);
          }
        }
      ],
      help: [
        {
          label: formatMessage({ id: "submitYourFeedback" }),
          onExecute: () => {
            ModalActions.sendFeedback();
          }
        }
      ]
    };
    const currentPage = MainFunctions.detectPageType();
    if (!currentPage.includes("preferences")) {
      defaultItems.profile.push({
        label: formatMessage({ id: "myPreferences" }),
        onExecute: () => {
          ApplicationActions.changePage(
            `${ApplicationStore.getApplicationSetting(
              "UIPrefix"
            )}profile/preferences`
          );
        }
      });
    }
    if (
      currentPage.indexOf("files") === -1 ||
      currentPage.indexOf("search") !== -1
    ) {
      defaultItems.profile.push({
        label: formatMessage({ id: "myDrawings" }),
        onExecute: () => {
          ApplicationActions.changePage(
            `${ApplicationStore.getApplicationSetting("UIPrefix")}files/-1`
          );
        }
      });
    }
    if (
      !currentPage.includes("templates") &&
      !currentPage.includes("public") &&
      UserInfoStore.getUserInfo("isAdmin") === true
    ) {
      defaultItems.profile.push({
        label: formatMessage({ id: "publictemplates" }),
        onExecute: () => {
          ApplicationActions.changePage(
            `${ApplicationStore.getApplicationSetting(
              "UIPrefix"
            )}templates/public`
          );
        }
      });
    }
    if (!currentPage.includes("templates")) {
      defaultItems.profile.push({
        label: formatMessage({ id: "customtemplates" }),
        onExecute: () => {
          ApplicationActions.changePage(
            `${ApplicationStore.getApplicationSetting("UIPrefix")}templates/my`
          );
        }
      });
    }
    if (
      currentPage !== "users" &&
      UserInfoStore.getUserInfo("isAdmin") === true
    ) {
      defaultItems.profile.push({
        label: formatMessage({ id: "users" }),
        onExecute: () => {
          ApplicationActions.changePage(
            `${ApplicationStore.getApplicationSetting("UIPrefix")}users`
          );
        }
      });
    }
    defaultItems.profile.push({
      label: formatMessage({ id: "webGLTest" }),
      onExecute: () => {
        ApplicationActions.changePage(
          `${ApplicationStore.getApplicationSetting("UIPrefix")}check`
        );
      }
    });
    defaultItems.profile.push({
      label: formatMessage({ id: "logout" }),
      onExecute: () => {
        DSHeader.initiateLogout();
      }
    });
    if (currentPage === "file" && DSHeader.getEditRightsControl()) {
      defaultItems.share.push({
        label: formatMessage({ id: "switchToEditMode" }),
        onExecute: () => {
          ModalActions.upgradeFileSession(FilesListStore.getCurrentFile()._id);
        }
      });
    }
    return defaultItems;
  }

  setTopBarContent = () => {
    theTopBarProxyInstance.setContent(DSHeader.getMenusItems());
    const topBarLogoDOMElement = document.getElementsByClassName("topbar-logo");
    if (topBarLogoDOMElement[0]) {
      const { changePageType } = this.props;
      topBarLogoDOMElement[0].addEventListener("click", changePageType);
    }
    /* const shareButton = document.getElementsByClassName(
      'topbar-menu-item share')
    if (shareButton[ 0 ]) {
      shareButton[ 0 ].addEventListener('click', () => {
        ModalActions.shareManagement(FilesListStore.getCurrentFile()._id)
      })
    } */
    const { isTopBarShown } = this.state;
    if (document.getElementById("topbar") && isTopBarShown === false) {
      this.setState({
        isTopBarShown: true
      });
      TableActions.recalculateTableDimensions();
    }
    DSHeader.checkShareVisibility();
  };

  loadTopBar = () => {
    const topBarDOMElement = document.getElementById("topbar");
    const { userName } = this.state;
    if (topBarDOMElement) {
      topBarDOMElement.style.removeProperty("display");
    }
    DSHeader.loadAMDModule().then(() => {
      if (DSTopFrame.isInitialized === false) {
        DSTopFrame.init(initialConfig(this.state));
      } else if (DSTopBar && DSTopBar.set) {
        DSTopBar.set({
          userName
        });
      }
      this.setTopBarContent();
    });
  };

  render() {
    return null;
  }
}

export default injectIntl(DSHeader);
