import AppDispatcher from "../dispatcher/AppDispatcher";
import * as SearchConstants from "../constants/SearchConstants";

// IA: this is the first iteration of search page refactoring
// It`s good to have storage that will keep search results
// all search methods for get search results and manipulate with them
export default class SearchActions {
  static removeFromSearchResults(tableId, ids) {
    AppDispatcher.dispatch({
      actionType: SearchConstants.REMOVE_SEARCH_RESULT,
      tableId,
      ids
    });
  }
}
