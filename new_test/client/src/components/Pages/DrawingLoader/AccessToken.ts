import { PrimitiveType } from "react-intl";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import MainFunctions from "../../../libraries/MainFunctions";
import userInfoStore from "../../../stores/UserInfoStore";
import Storage from "../../../utils/Storage";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import applicationStore from "../../../stores/ApplicationStore";

export type IncomingAccessTokenParameters = {
  server: string;
  token?: string;
  password?: string;
  isViewOnly: boolean;
  sessionId?: string;
  versionId?: string;
  locale: string;
  ribbonMode: boolean;
  testset?: string;
  sheetId?: string;
};

export type XenonAccessTokenParameters = IncomingAccessTokenParameters & {
  plpass?: string;
  access?: string;
  file?: string;
  apitoken?: string;
};
export default class AccessToken {
  static async create(
    fileId: string,
    parameters: IncomingAccessTokenParameters
  ) {
    const finalParameters = this.getProperParameters(fileId, parameters);
    try {
      return this.generateWithURL("", finalParameters);
    } catch (err) {
      SnackbarUtils.alertError(err.message);
    }
    return "";
  }

  static getProperParameters(
    fileId: string,
    parameters: IncomingAccessTokenParameters
  ) {
    const xenonParameters: XenonAccessTokenParameters = parameters;
    // https://graebert.atlassian.net/browse/XENON-51851
    if (
      fileId.startsWith("BX+") &&
      fileId.endsWith("+0") &&
      (xenonParameters.sessionId || "").length === 0
    ) {
      xenonParameters.token = "BOX_USER";
    }
    if (xenonParameters.token && xenonParameters.sessionId) {
      delete xenonParameters.sessionId;
    }
    if (xenonParameters.password) {
      xenonParameters.plpass = xenonParameters.password;
    }
    const { isViewOnly } = parameters;
    if (isViewOnly) {
      xenonParameters.access = "view";
    }
    xenonParameters.file = fileId;
    const additionalParameters = JSON.parse(
      Storage.getItem("additionalParameters") || "{}"
    );
    return { ...xenonParameters, ...additionalParameters };
  }

  static async generateWithURL(
    customURL: string,
    finalParameters: Record<string, PrimitiveType>
  ) {
    const { data } = await Requests.sendGenericRequest(
      "/token/generate",
      RequestsMethods.POST,
      Requests.getDefaultUserHeaders(),
      finalParameters,
      ["*"]
    );
    finalParameters.apitoken = data.token;
    return MainFunctions.injectIntoString(
      customURL ||
        Storage.getItem("customEditorURL") ||
        applicationStore.getApplicationSetting("editorURL"),
      finalParameters
    );
  }

  static calculateViewMode(name: string, viewOnly: boolean) {
    // if token - view
    if (((MainFunctions.QueryString("token") as string) || "").length > 0) {
      return true;
    }
    // access forced to be view
    if (MainFunctions.QueryString("access") === "view") {
      return true;
    }
    // version
    if (((MainFunctions.QueryString("versionId") as string) || "").length > 0) {
      return true;
    }
    // revit
    const fileExtension = MainFunctions.getExtensionFromName(name);
    if (fileExtension === "rvt" || fileExtension === "rfa") {
      return true;
    }
    const isUserInfoLoaded =
      userInfoStore.getUserInfo("isLoggedIn") &&
      userInfoStore.getUserInfo("isFullInfo");
    // free account - no edit
    if (isUserInfoLoaded && userInfoStore.isFreeAccount()) {
      return true;
    }
    // editing disabled for user
    if (
      isUserInfoLoaded &&
      userInfoStore.getUserInfo("options").editor === false
    ) {
      return true;
    }
    // view only flag from file info
    if (viewOnly) {
      return !!viewOnly;
    }
    return true;
  }
}
