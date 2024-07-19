import React from "react";
import _ from "underscore";
import { Router, Route, browserHistory, IndexRoute } from "react-router";
import PropTypes from "prop-types";
import { IntlProvider } from "react-intl";
import { flattenMessages } from "../../utils/flattenMessages";
import Storage from "../../utils/Storage";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationStore, { UPDATE } from "../../stores/ApplicationStore";
import UserInfoStore from "../../stores/UserInfoStore";
import UserInfoActions from "../../actions/UserInfoActions";
import AppEntryComponent from "../AppEntryComponent";
import ApplicationActions from "../../actions/ApplicationActions";
import PermissionsPage from "../Pages/PermissionsPage/PermissionsPage";
import UtmTracker from "../../utils/UtmTracker";
import { normalizeLocaleAndLang } from "../../utils/languages";
import English from "../../assets/translations/en.json";

const licensesPage = React.lazy(() =>
  import("../Pages/LicensesPage/LicensesPage")
);

const indexPage = React.lazy(() =>
  import(/* webpackChunkName: "IndexPage" */ "../Pages/IndexPage/IndexPage")
);
const usersPage = React.lazy(() =>
  import(/* webpackChunkName: "UsersPage" */ "../Pages/UsersPage/UsersPage")
);
const webGLTestPage = React.lazy(() =>
  import(
    /* webpackChunkName: "WebGLTestPage" */ "../Pages/WebGLTestPage/WebGLTestPage"
  )
);
const trialPage = React.lazy(() =>
  import(/* webpackChunkName: "TrialPage" */ "../Pages/TrialPage/TrialPage")
);
const filesPage = React.lazy(() =>
  import(/* webpackChunkName: "FileLoader" */ "../Pages/FileLoader/FileLoader")
);
const profilePageNew = React.lazy(() =>
  import(
    /* webpackChunkName: "ProfilePage" */ "../Pages/ProfilePageNew/ProfilePage"
  )
);
const filesSearchPage = React.lazy(() =>
  import(
    /* webpackChunkName: "SearchLoader" */ "../Pages/SearchLoader/SearchLoader"
  )
);
const drawingPage = React.lazy(() =>
  import(
    /* webpackChunkName: "DrawingLoader" */ "../Pages/DrawingLoader/DrawingLoader"
  )
);

const resourcesNewPage = React.lazy(() =>
  import(
    /* webpackChunkName: "ResourcesLoader" */ "../Pages/ResourcesNewPage/ResourcesLoader"
  )
);

const storagesPage = React.lazy(() =>
  import(
    /* webpackChunkName: "StoragesPage" */ "../Pages/StoragesPage/StoragesPage"
  )
);
const notificationPage = React.lazy(() =>
  import(/* webpackChunkName: "NotifyLoader" */ "../Pages/NotifyLoader")
);
const openInAppPage = React.lazy(() =>
  import(/* webpackChunkName: "OpenInAppPage" */ "../Pages/OpenInApp/OpenInApp")
);
const downloadLinkPage = React.lazy(() =>
  import(
    /* webpackChunkName: "OpenInAppPage" */ "../Pages/LinkPage/DownloadLinkPage"
  )
);
const companyPage = React.lazy(() =>
  import(
    /* webpackChunkName: "CompanyPage" */ "../Pages/CompanyPage/CompanyPage"
  )
);
const privacyPage = React.lazy(() =>
  import(
    /* webpackChunkName: "PrivacyPolicyPage" */ "../Pages/PrivacyPolicyPage/PrivacyPolicyPage"
  )
);

const safeZoneRoutes = [
  "index",
  "signup",
  "notify",
  "privacy",
  "terms",
  "open",
  "licenses"
];

class RouterOverlay extends React.Component {
  static triggerTransitionSideEffects() {
    setTimeout(() => {
      UtmTracker.updateLinks();
    }, 100);

    const currentPageUrl =
      location.pathname.substr(
        location.pathname.indexOf(window.ARESKudoConfigObject.UIPrefix) + 1
      ) + location.search;
    ApplicationActions.emitUpdate();
    if (safeZoneRoutes.indexOf(MainFunctions.detectPageType()) === -1) {
      // if user is trying to access page that requires authentication
      if (
        !Storage.getItem("sessionId") &&
        ((!MainFunctions.QueryString("token") &&
          location.href.indexOf("external") === -1) ||
          MainFunctions.detectPageType() !== "file") &&
        MainFunctions.detectPageType() !== "trial"
      ) {
        Storage.store("error", "notLoggedInOrSessionExpired");
        browserHistory.push(
          `${
            window.ARESKudoConfigObject.UIPrefix
          }?redirect=${encodeURIComponent(currentPageUrl)}`
        );
        // redirect to index page with appropriate error message
        // and redirect url
      } else if (
        Storage.getItem("sessionId") &&
        !UserInfoStore.getUserInfo("isLoggedIn")
      ) {
        UserInfoActions.getUserInfo();
      }
    }
  }

  static propTypes = {
    language: PropTypes.string.isRequired,
    locale: PropTypes.string.isRequired,
    isEnforced: PropTypes.bool,
    messages: PropTypes.objectOf(PropTypes.string).isRequired
  };

  static defaultProps = {
    isEnforced: false
  };

  constructor(props) {
    super(props);
    const { language, locale, messages } = props;
    this.state = {
      language,
      locale,
      messages
    };
  }

  componentDidMount() {
    RouterOverlay.setLogoutTimer();
    ApplicationStore.addChangeListener(UPDATE, this.onAppChange);
  }

  static setLogoutTimer() {
    window.logoutTimer = setInterval(
      () => {
        UserInfoActions.checkSession().catch(
          ApplicationStore.sessionExpiredHandler
        );
      },
      29 * 60 * 1000
    );
  }

  componentWillUnmount() {
    clearInterval(window.logoutTimer);
    ApplicationStore.removeChangeListener(UPDATE, this.onAppChange);
  }

  onAppChange = () => {
    const { isEnforced } = this.props;
    const { language, locale } = this.state;
    if (
      language !== Storage.store("lang") ||
      locale !== Storage.store("locale")
    ) {
      if (isEnforced) {
        Storage.store("lang", language);
        Storage.store("locale", locale);
      } else {
        this.loadMessagesPerLocale(Storage.store("locale"));
      }
    }
  };

  loadMessagesPerLocale = initialLocale => {
    const { language, locale } = normalizeLocaleAndLang(initialLocale);
    import(`../../assets/translations/${language}.json`).then(messages => {
      this.setState({
        messages,
        locale,
        language
      });
    });
  };

  render() {
    const { language, messages, locale } = this.state;
    // there is no such code as "cn".
    // Chinese traditional (CP = zh, real = zh-Hant) and Chinese simplified (CP = cn, real = zh-Hans)
    // are both stored in zh file in react-intl
    let intlLocale = language;
    if (language === "cn") {
      intlLocale = "zh-Hans";
    } else if (language === "zh") {
      intlLocale = "zh-Hant";
    }
    return (
      <IntlProvider
        locale={intlLocale}
        messages={{ ...flattenMessages(English), ...flattenMessages(messages) }}
        key={locale}
      >
        <Router
          history={browserHistory}
          onUpdate={RouterOverlay.triggerTransitionSideEffects}
        >
          <Route
            path={window.ARESKudoConfigObject.UIPrefix}
            component={AppEntryComponent}
          >
            <IndexRoute component={indexPage} />
            <Route path="index" component={indexPage} />
            <Route path="signup" component={indexPage} />
            <Route path="users" component={usersPage}>
              <Route path="find/:query" />
              <Route path="*" />
            </Route>
            <Route path="licenses" component={licensesPage}>
              <Route path=":type" />
            </Route>
            <Route path="webgltest" component={webGLTestPage} />
            <Route path="check" component={webGLTestPage} />
            <Route path="trial" component={trialPage} />
            <Route path="files/search/:query" component={filesSearchPage} />
            <Route path="files" component={filesPage}>
              <Route path="unsubscribe/:fileId" />
              <Route path=":storage/:account/:folder" />
              <Route path=":id" />
              <Route path="trash" />
              <Route path="trash/:storage/:account/:folder" />
              <Route path="trash/:id" />
              <Route path="*" />
            </Route>
            <Route path="app">
              <Route path="file/:id/permissions" component={PermissionsPage} />
            </Route>
            <Route path="file/:fileId" component={drawingPage} />
            <Route path="users" component={usersPage} />
            <Route path="resources" component={resourcesNewPage}>
              <Route path="templates">
                <Route path="my">
                  <Route path=":libId" />
                </Route>
                <Route path="public">
                  <Route path=":libId" />
                </Route>
              </Route>
              <Route path="fonts">
                <Route path="my">
                  <Route path=":libId" />
                </Route>
                <Route path="public">
                  <Route path=":libId" />
                </Route>
              </Route>
              <Route path="blocks">
                <Route path="find/:query" />
                <Route path=":libId" />
              </Route>
            </Route>
            {/* <Route path="profile" component={profilePage}>
              <Route path="account" />
              <Route path="preferences" />
              <Route path="*" />
            </Route> */}
            <Route path="profile" component={profilePageNew}>
              <Route path="account" />
              <Route path="preferences" />
              <Route path="*" />
            </Route>
            <Route path="storages" component={storagesPage} />
            <Route path="notify" component={notificationPage}>
              <Route path=":mode/:type/:bredirect" />
            </Route>
            <Route path="terms" component={privacyPage} />
            <Route path="company" component={companyPage} />
            <Route path="commander">
              <Route path="storages" component={storagesPage} />
              <Route path="*" />
            </Route>
            <Route path="open/:fileId" component={openInAppPage} />
            <Route
              path="file/:fileId/version/:versionId/link"
              component={downloadLinkPage}
            />
            <Route path="*" component={indexPage} />
          </Route>
        </Router>
      </IntlProvider>
    );
  }
}

export default RouterOverlay;
