import Requests from "../utils/Requests";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as FontsConstants from "../constants/FontsConstants";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";

const FontsActions = {
  getCustomFonts() {
    AppDispatcher.dispatch({
      actionType: FontsConstants.GET_CUSTOM_FONTS
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/fonts/user/`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.GET_CUSTOM_FONTS_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.GET_CUSTOM_FONTS_FAIL,
            info: response.data
          });
          reject(response);
        });
    });
  },
  getCompanyFonts(companyId) {
    AppDispatcher.dispatch({
      actionType: FontsConstants.GET_COMPANY_FONTS
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/fonts/company/${companyId}`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.GET_COMPANY_FONTS_SUCCESS,
            info: response.data,
            companyId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.GET_COMPANY_FONTS_FAIL,
            info: response.data,
            companyId
          });
          reject(response);
        });
    });
  },
  downloadFont(fontId) {
    AppDispatcher.dispatch({
      actionType: FontsConstants.DOWNLOAD_FONT
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/fonts/${fontId}`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.DOWNLOAD_FONT_SUCCESS,
            info: response.data,
            fontId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.DOWNLOAD_FONT_FAIL,
            info: response.data,
            fontId
          });
          reject(response);
        });
    });
  },
  uploadCustomFont(file) {
    AppDispatcher.dispatch({
      actionType: FontsConstants.UPLOAD_CUSTOM_FONT
    });
    return new Promise((resolve, reject) => {
      const formData = new FormData();
      formData.append(0, file);
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/fonts`,
        RequestsMethods.POST,
        headers,
        formData,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.UPLOAD_CUSTOM_FONT_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.UPLOAD_CUSTOM_FONT_FAIL,
            info: response.data
          });
          reject(response);
        });
    });
  },
  uploadCompanyFont(companyId, file) {
    AppDispatcher.dispatch({
      actionType: FontsConstants.UPLOAD_COMPANY_FONT
    });
    return new Promise((resolve, reject) => {
      const formData = new FormData();
      formData.append(0, file);
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/fonts/company/${companyId}`,
        RequestsMethods.POST,
        headers,
        formData,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.UPLOAD_COMPANY_FONT_SUCCESS,
            info: response.data,
            companyId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.UPLOAD_COMPANY_FONT_FAIL,
            info: response.data,
            companyId
          });
          reject(response);
        });
    });
  },
  remove(fontId) {
    AppDispatcher.dispatch({
      actionType: FontsConstants.REMOVE
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/fonts/${fontId}`,
        RequestsMethods.DELETE,
        headers,
        undefined,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.REMOVE_SUCCESS,
            info: response.data,
            fontId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: FontsConstants.REMOVE_FAIL,
            info: response.data,
            fontId
          });
          reject(response);
        });
    });
  }
};

export default FontsActions;
