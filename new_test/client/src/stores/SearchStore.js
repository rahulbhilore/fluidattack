import EventEmitter from "events";
import * as SearchConstants from "../constants/SearchConstants";
import AppDispatcher from "../dispatcher/AppDispatcher";

class SearchStore extends EventEmitter {
  constructor() {
    super();
    this.dispatcherIndex = AppDispatcher.register(this._handleAction);
  }

  _handleAction = action => {
    if (action.actionType.indexOf(SearchConstants.constantPrefix) === -1)
      return;

    switch (action.actionType) {
      case SearchConstants.REMOVE_SEARCH_RESULT: {
        this.emit(
          SearchConstants.REMOVE_SEARCH_RESULT,
          action.tableId,
          action.ids
        );
        break;
      }
      default:
    }
  };

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

SearchStore.dispatchToken = null;
const searchStore = new SearchStore();

export default searchStore;
