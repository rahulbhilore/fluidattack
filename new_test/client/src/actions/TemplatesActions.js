import $ from "jquery";
import * as TemplatesConstants from "../constants/TemplatesConstants";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import AppDispatcher from "../dispatcher/AppDispatcher";
import Requests from "../utils/Requests";
import MainFunctions from "../libraries/MainFunctions";
import ProcessActions from "./ProcessActions";
import Processes from "../constants/appConstants/Processes";

/**
 * @class
 * @classdesc Actions for work with Templates
 */
export default class TemplatesActions {
  /**
   * @function
   * @static
   * @public
   * @description Load list of templates
   * @param templateType {'templates'|'customtemplates'}
   */
  static loadTemplates(templateType) {
    AppDispatcher.dispatch({
      actionType: TemplatesConstants.TEMPLATE_LOAD,
      templateType
    });
    const headers = Requests.getDefaultUserHeaders();
    headers.templateType = "USER";
    if (templateType === TemplatesConstants.PUBLIC_TEMPLATES) {
      headers.templateType = "PUBLIC";
    }
    // ignore errors
    Requests.sendGenericRequest(
      "/templates",
      RequestsMethods.GET,
      headers,
      undefined,
      ["*"]
    ).then(response => {
      AppDispatcher.dispatch({
        actionType: TemplatesConstants.TEMPLATE_LOAD_SUCCESS,
        templates: response.data.results,
        templateType
      });
    });
  }

  static deleteTemplates(templatesArray, templatesType) {
    if (templatesArray.length === 1) {
      TemplatesActions.deleteTemplate(templatesArray[0], templatesType).then(
        result => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_DELETE_FINISHED,
            responses: [result],
            templatesType
          });
        }
      );
    } else {
      TemplatesActions.deleteTemplatesBulk(templatesArray, templatesType).then(
        result => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_DELETE_FINISHED,
            responses: result,
            templatesType
          });
        }
      );
    }
  }

  static deleteTemplatesBulk(templatesArray, templateType) {
    AppDispatcher.dispatch({
      actionType: TemplatesConstants.TEMPLATE_DELETE,
      templatesArray,
      templateType
    });
    const apiEndpoint =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES
        ? "admin/templates"
        : "templates";
    const headers = Requests.getDefaultUserHeaders();
    headers.templateType =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES ? "PUBLIC" : "USER";
    headers.ids = templatesArray.join(",");
    return new Promise(resolve => {
      Requests.sendGenericRequest(
        `/${apiEndpoint}`,
        RequestsMethods.DELETE,
        headers,
        undefined,
        ["*"]
      )
        .then(() => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_DELETE_SUCCESS,
            templatesArray,
            templateType
          });
          resolve(
            templatesArray.map(templateId => ({ id: templateId, err: null }))
          );
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_DELETE_FAIL,
            templatesArray,
            templateType,
            err: err.text
          });
          resolve(
            templatesArray.map(templateId => ({
              id: templateId,
              err: err.text
            }))
          );
        });
    });
  }

  /**
   * @function
   * @static
   * @public
   * @description Delete template
   * @param templateId {string}
   * @param templateType {'templates'|'customtemplates'}
   */
  static deleteTemplate(templateId, templateType) {
    AppDispatcher.dispatch({
      actionType: TemplatesConstants.TEMPLATE_DELETE,
      templateId,
      templateType
    });
    const apiEndpoint =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES
        ? "admin/templates"
        : "templates";
    const headers = Requests.getDefaultUserHeaders();
    headers.templateType =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES ? "PUBLIC" : "USER";
    return new Promise(resolve => {
      Requests.sendGenericRequest(
        `/${apiEndpoint}/${templateId}`,
        RequestsMethods.DELETE,
        headers,
        undefined,
        ["*"]
      )
        .then(() => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_DELETE_SUCCESS,
            templateId,
            templateType
          });
          resolve({ id: templateId, err: null });
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_DELETE_FAIL,
            templateId,
            templateType,
            err
          });
          resolve({ id: templateId, err });
        });
    });
  }
  /* eslint-disable */
  /**
   * @param templateType
   * @param body
   * @returns {Promise<unknown>}
   */
  static uploadTemplate(templateType, body) {
    const fd = new FormData();
    fd.append(0, body);
    const apiEndpoint =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES
        ? "/admin/templates"
        : "/templates";
    const headers = Requests.getDefaultUserHeaders();
    const oldHeaders = Object.entries(headers).map(([k, v]) => ({
      name: k,
      value: v
    }));
    oldHeaders.push({
      name: "templateType",
      value:
        templateType === TemplatesConstants.PUBLIC_TEMPLATES ? "PUBLIC" : "USER"
    });
    const newTemplateId = MainFunctions.guid();
    TemplatesActions.addTemplate({
      _id: newTemplateId,
      id: newTemplateId,
      type: templateType,
      name: body.name
    }, templateType);
    ProcessActions.start(newTemplateId, Processes.UPLOADING);
    return new Promise((resolve, reject) => {
      Requests.uploadFile(
        window.ARESKudoConfigObject.api + apiEndpoint,
        fd,
        oldHeaders,
        response => {
          ProcessActions.end(newTemplateId);
          if (response.status === 413) {
            // largeEntity
            AppDispatcher.dispatch({
              actionType: TemplatesConstants.TEMPLATE_UPLOAD_FAIL,
              templateType,
              oldId: newTemplateId,
              err: new Error("largeEntity"),
            });
            reject(new Error("largeEntity"));
          } else if (response.status !== 200) {
            const message = response.data?.message || "Unknown error";
            // generic error
            AppDispatcher.dispatch({
              actionType: TemplatesConstants.TEMPLATE_UPLOAD_FAIL,
              templateType,
              oldId: newTemplateId,
              err: new Error(message),
            });
            reject(new Error(message));
          } else {
            AppDispatcher.dispatch({
              actionType: TemplatesConstants.TEMPLATE_UPLOAD_SUCCESS,
              templateType,
              templateId: response.data._id,
              oldId: newTemplateId,
            });
            // success
            resolve(response.data._id);
          }
        },
        null,
        progressEvt => {
          if (
            !progressEvt.event ||
            !progressEvt.event.lengthComputable ||
            progressEvt.event.lengthComputable !== true
          ) {
            return;
          }
          const loaded = progressEvt.loaded / progressEvt.total;
          if (loaded !== 1) {
            const percentage = (loaded * 100).toFixed(2);
            ProcessActions.step(newTemplateId, percentage);
          }
        });
    });
  }

  /**
   * @param templateType
   * @param id
   * @param newName
   * @returns {Promise<unknown>}
   */
  static renameTemplate(templateType, id, newName) {
    AppDispatcher.dispatch({
      actionType: TemplatesConstants.TEMPLATE_RENAME,
      templateType,
      id,
      name: newName
    });
    const apiEndpoint =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES
        ? "/admin/templates"
        : "/templates";
    const headers = Requests.getDefaultUserHeaders();
    headers.templateType =
      templateType === TemplatesConstants.PUBLIC_TEMPLATES ? "PUBLIC" : "USER";
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `${apiEndpoint}/${id}`,
        RequestsMethods.PUT,
        headers,
        { name: newName },
        ["*"]
      )
        .then(() => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_RENAME_SUCCESS,
            templateType,
            id,
            name: newName
          });
          resolve();
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: TemplatesConstants.TEMPLATE_RENAME_FAIL,
            templateType,
            id,
            name: newName,
            err
          });
          reject(err);
        });
    });
  }

  static addTemplate(templateData, templateType, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: TemplatesConstants.ADD_TEMPLATE,
      templateData,
      templateType,
      fireEvent
    });
  }

  /**
   * @param templateId
   * @param templateType
   * @param newTemplateData
   * @param omitProperties
   * @param fireEvent
   */
  static modifyTemplate(
    templateId,
    templateType,
    newTemplateData,
    omitProperties,
    fireEvent = true
  ) {
    AppDispatcher.dispatch({
      actionType: TemplatesConstants.MODIFY_TEMPLATE,
      templateId,
      templateType,
      newTemplateData,
      omitProperties,
      fireEvent
    });
  }
}
