import * as ApplicationConstants from "../constants/ApplicationConstants";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import AppDispatcher from "../dispatcher/AppDispatcher";
import Requests from "../utils/Requests";

/**
 * @class
 * @classdesc Actions for changing overall Application state
 */
export default class ApplicationActions {
  /**
   * @function
   * @static
   * @public
   * @description Load Kudo configuration
   */
  static loadApplicationConfiguration() {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_LOAD_CONFIGURATION
    });
    Requests.sendGenericRequest(
      `${location.origin}/configs/config.json`,
      RequestsMethods.GET,
      undefined,
      undefined,
      []
    )
      .then(applicationInfo => {
        AppDispatcher.dispatch({
          actionType: ApplicationConstants.APP_CONFIGURATION_LOADED,
          applicationInfo: applicationInfo.data
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: ApplicationConstants.APP_CONFIGURATION_LOAD_FAILED,
          err
        });
      });
  }

  /**
   * @function
   * @static
   * @public
   * @description Load Kudo revision
   */
  static loadRevisionNumber() {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_LOAD_REVISION
    });
    Requests.sendGenericRequest(
      "/revision",
      RequestsMethods.GET,
      undefined,
      undefined,
      []
    )
      .then(revisionData => {
        AppDispatcher.dispatch({
          actionType: ApplicationConstants.APP_REVISION_LOADED,
          revision: revisionData.data.revision
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: ApplicationConstants.APP_REVISION_LOAD_FAILED,
          err
        });
      });
  }

  /**
   * @function
   * @static
   * @public
   * @description Reload UI fully without page reloading
   * @param [fullReload] {boolean} - whether reload should be made with or
   * without replacing every asset
   */
  static triggerApplicationReload(fullReload) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_RELOAD,
      fullReload
    });
  }

  /**
   * @function
   * @static
   * @public
   * @description Change the URL (overlay over browserHistory.push)
   * @param newPage {string}
   * @param [callerTag] {string}
   */
  static changePage(newPage, callerTag) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_CHANGE_PAGE,
      newPage,
      callerTag
    });
  }

  /**
   * @function
   * @static
   * @public
   * @description Change APP language
   * @param lang {string}
   * @param locale {string}
   */
  static changeLanguage(lang, locale) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_CHANGE_LANGUAGE,
      lang,
      locale
    });
  }

  static toggleTermsVisibility() {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_TOGGLE_TERMS
    });
  }

  static setColorScheme(scheme) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_COLOR_SCHEME_CHANGED,
      scheme
    });
  }

  static switchSidebar(state = null) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_SIDEBAR_SWITCH,
      state
    });
  }

  static switchSearchCollapse(state = null) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_SEARCH_COLLAPSE_SWITCH,
      state
    });
  }

  static switchUserMenu(state) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_USER_MENU_SWITCH,
      state
    });
  }

  static switchDrawingMenu(state) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_DRAWING_MENU_SWITCH,
      state
    });
  }

  static switchOldResources(isOld = false) {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_SWITCH_OLD_RESOURCES,
      isOld
    });
  }

  /**
   * @function
   * @static
   * @public
   * @description Emit UPDATE event
   * @link https://graebert.atlassian.net/browse/XENON-31889
   */
  static emitUpdate() {
    AppDispatcher.dispatch({
      actionType: ApplicationConstants.APP_UPDATE
    });
  }
}
