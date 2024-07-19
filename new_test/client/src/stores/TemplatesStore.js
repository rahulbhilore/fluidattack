/**
 * Created by developer123 on 13.02.15.
 */
import EventEmitter from "events";
import _ from "underscore";
import Immutable, { List } from "immutable";
import * as TemplatesConstants from "../constants/TemplatesConstants";
import AppDispatcher from "../dispatcher/AppDispatcher";

import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";

export const TEMPLATES_LOADED = "TEMPLATES_LOADED";
export const CUSTOM_TEMPLATES_LOADED = "CUSTOM_TEMPLATES_LOADED";
export const TEMPLATE_DELETED = "TEMPLATE_DELETED";
export const TEMPLATE_UPLOADED = "TEMPLATE_UPLOADED";
export const TEMPLATE_UPDATED = "TEMPLATE_UPDATED";

class TemplatesStore extends EventEmitter {
  constructor(props) {
    super(props);
    this[TemplatesConstants.PUBLIC_TEMPLATES] = {
      items: new List(),
      timestamp: null
    };
    this[TemplatesConstants.CUSTOM_TEMPLATES] = {
      items: new List(),
      timestamp: null
    };
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  saveTemplates(templateType, items) {
    this[templateType] = {
      items: Immutable.fromJS(
        _.map(items, template => {
          template.id = template._id;
          template.type = "template";
          template.actions = { edit: { name: false, comment: false } };
          return template;
        })
      ),
      timestamp: Date.now()
    };
  }

  showDeleteMessage(responses, templatesType) {
    const successful = _.where(responses, { err: null });
    const failed = _.filter(responses, response => response.err !== null);
    const successfulNames = _.map(successful, template =>
      this[templatesType].items
        .find(t => t.get("id") === template.id)
        .get("name")
    ).join(", ");
    const failedNames = _.map(
      failed,
      template =>
        `${this[templatesType].items
          .find(t => t.get("id") === template.id)
          .get("name")} : ${template.err}`
    ).join("\r\n");
    const removedIds = successful.map(({ id }) => id);
    this[templatesType].items = this[templatesType].items.filter(
      t => !removedIds.includes(t.get("id"))
    );
    if (failed.length) {
      if (successful.length) {
        SnackbarUtils.alertWarning({
          id: "templateDeleted",
          name: `${successfulNames}\n\r${failedNames}`
        });
      } else {
        SnackbarUtils.alertError(failedNames);
      }
    } else if (successful.length) {
      SnackbarUtils.alertOk({ id: "templateDeleted", name: successfulNames });
    }
  }

  getTemplates(templateType) {
    return this[templateType].items;
  }

  modifyTemplate(templateId, templateType, newTemplateData) {
    const targetTemplates = this[templateType].items.toJS();
    const targetIndex = targetTemplates.findIndex(
      elem => elem._id === templateId
    );
    targetTemplates[targetIndex] = _.extend(
      targetTemplates[targetIndex],
      newTemplateData
    );
    this[templateType].items = Immutable.fromJS(targetTemplates);
  }

  addTemplate(templateData, templateType) {
    const targetTemplates = this[templateType].items.toJS();
    targetTemplates.push(templateData);
    this[templateType].items = Immutable.fromJS(targetTemplates);
  }

  deleteTemplate(templateId, templateType) {
    this[templateType].items = this[templateType].items.filter(
      template => template.get("id") !== templateId
    );
  }

  handleAction(action) {
    if (action.actionType.indexOf(TemplatesConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case TemplatesConstants.TEMPLATE_LOAD_SUCCESS:
          this.saveTemplates(action.templateType, action.templates);
          if (action.templateType === TemplatesConstants.CUSTOM_TEMPLATES) {
            this.emitEvent(CUSTOM_TEMPLATES_LOADED);
          } else {
            this.emitEvent(TEMPLATES_LOADED);
          }
          break;
        case TemplatesConstants.TEMPLATE_DELETE_FINISHED:
          this.showDeleteMessage(action.responses, action.templatesType);
          this.emitEvent(TEMPLATE_UPDATED);
          break;
        case TemplatesConstants.TEMPLATE_UPLOAD_SUCCESS:
          this.modifyTemplate(action.oldId, action.templateType, {
            id: action.templateId,
            _id: action.templateId
          });
          this.emitEvent(TEMPLATE_UPDATED);
          break;
        case TemplatesConstants.TEMPLATE_UPLOAD_FAIL:
          this.deleteTemplate(action.oldId);
          this.emitEvent(TEMPLATE_UPDATED);
          break;
        case TemplatesConstants.MODIFY_TEMPLATE: {
          this.modifyTemplate(
            action.templateId,
            action.templateType,
            action.newTemplateData,
            action.omitProperties,
            action.update
          );
          this.emitEvent(TEMPLATE_UPDATED);
          break;
        }
        case TemplatesConstants.ADD_TEMPLATE: {
          this.addTemplate(action.templateData, action.templateType);
          if (action.fireEvent) this.emit(TEMPLATE_UPDATED);
          break;
        }
        default:
          break;
      }
    }
  }

  emitEvent(eventType) {
    this.emit(eventType);
  }

  addChangeListener(eventType, callback) {
    this.on(eventType, callback);
  }

  removeChangeListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }
}

TemplatesStore.dispatchToken = null;

export default new TemplatesStore();
