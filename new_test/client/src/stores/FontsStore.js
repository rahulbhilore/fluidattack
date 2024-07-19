import EventEmitter from "events";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as FontsConstants from "../constants/FontsConstants";

class FontsStore extends EventEmitter {
  constructor() {
    super();
    this.dispatcherIndex = AppDispatcher.register(this.handleAction);
    this.customFonts = [];
    this.companyFonts = [];
  }

  handleAction = action => {
    if (action.actionType.indexOf(FontsConstants.constantPrefix) === -1) return;

    switch (action.actionType) {
      case FontsConstants.GET_CUSTOM_FONTS: {
        this.emitEvent(FontsConstants.GET_CUSTOM_FONTS);
        break;
      }
      case FontsConstants.GET_CUSTOM_FONTS_SUCCESS: {
        this.customFonts = action.info.fonts;
        this.emitEvent(FontsConstants.GET_CUSTOM_FONTS_SUCCESS);
        break;
      }
      case FontsConstants.GET_CUSTOM_FONTS_FAIL: {
        this.emitEvent(FontsConstants.GET_CUSTOM_FONTS_FAIL);
        break;
      }
      case FontsConstants.GET_COMPANY_FONTS: {
        this.emitEvent(FontsConstants.GET_COMPANY_FONTS);
        break;
      }
      case FontsConstants.GET_COMPANY_FONTS_SUCCESS: {
        this.companyFonts = action.info.fonts;
        this.emitEvent(FontsConstants.GET_COMPANY_FONTS_SUCCESS);
        break;
      }
      case FontsConstants.GET_COMPANY_FONTS_FAIL: {
        this.emitEvent(FontsConstants.GET_COMPANY_FONTS_FAIL);
        break;
      }
      case FontsConstants.DOWNLOAD_FONT: {
        this.emitEvent(FontsConstants.DOWNLOAD_FONT);
        break;
      }
      case FontsConstants.DOWNLOAD_FONT_SUCCESS: {
        this.emitEvent(FontsConstants.DOWNLOAD_FONT_SUCCESS);
        break;
      }
      case FontsConstants.DOWNLOAD_FONT_FAIL: {
        this.emitEvent(FontsConstants.DOWNLOAD_FONT_FAIL);
        break;
      }
      case FontsConstants.UPLOAD_CUSTOM_FONT: {
        this.emitEvent(FontsConstants.UPLOAD_CUSTOM_FONT);
        break;
      }
      case FontsConstants.UPLOAD_CUSTOM_FONT_SUCCESS: {
        this.emitEvent(FontsConstants.UPLOAD_CUSTOM_FONT_SUCCESS);
        break;
      }
      case FontsConstants.UPLOAD_CUSTOM_FONT_FAIL: {
        this.emitEvent(FontsConstants.UPLOAD_CUSTOM_FONT_FAIL);
        break;
      }
      case FontsConstants.UPLOAD_COMPANY_FONT: {
        this.emitEvent(FontsConstants.UPLOAD_COMPANY_FONT);
        break;
      }
      case FontsConstants.UPLOAD_COMPANY_FONT_SUCCESS: {
        this.emitEvent(FontsConstants.UPLOAD_COMPANY_FONT_SUCCESS);
        break;
      }
      case FontsConstants.UPLOAD_COMPANY_FONT_FAIL: {
        this.emitEvent(FontsConstants.UPLOAD_COMPANY_FONT_FAIL);
        break;
      }
      case FontsConstants.REMOVE: {
        this.emitEvent(FontsConstants.REMOVE);
        break;
      }
      case FontsConstants.REMOVE_SUCCESS: {
        this.emitEvent(FontsConstants.REMOVE_SUCCESS);
        break;
      }
      case FontsConstants.REMOVE_FAIL: {
        this.emitEvent(FontsConstants.REMOVE_FAIL);
        break;
      }
      default:
        break;
    }
  };

  getCustomFonts() {
    return this.customFonts;
  }

  getCompanyFonts() {
    return this.companyFonts;
  }

  /**
   * @private
   * @param eventType {string}
   */
  emitEvent(eventType) {
    this.emit(eventType);
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  addChangeListener(eventType, callback) {
    this.on(eventType, callback);
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  removeChangeListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }
}

FontsStore.dispatchToken = null;
const fontsStore = new FontsStore();

export default fontsStore;
