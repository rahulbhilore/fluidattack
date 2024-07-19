export const FOLDERS = "folders";
export const TRASH = "trash";
export const INFO = "info";
export const PATH = "path";
// const USERS = "users";
// const REVISION = "revision";
// const RECENT = "recent";

let abortsArray = [];

const FetchAbortsControl = {
  /**
   * Detects type of API request by url
   * @param url
   * @returns {string|null}
   */
  getRequestType(url) {
    if (url.includes(FOLDERS) && !(url.includes(INFO) || url.includes(PATH)))
      return FOLDERS;
    if (url.includes(TRASH)) return TRASH;
    if (url.includes(INFO)) return INFO;

    return null;
  },
  /**
   * Add signal, do not use this directly
   * @param url
   * @returns {AbortSignal|null}
   * @private
   */
  _addSignalHandler(url, actionId = null) {
    const type = this.getRequestType(url);

    if (
      !type &&
      !url.includes("presignedUploadedFiles") &&
      !url.includes("simple-storage")
    )
      return null;

    const abortController = new AbortController();
    abortsArray.push({
      url,
      type,
      abortController,
      actionId
    });
    return abortController.signal;
  },
  /**
   * Remove signal, do not use this directly
   * @param url
   * @returns {void}
   * @private
   */
  _removeSignalHandler(url) {
    const type = this.getRequestType(url);

    if (
      !type &&
      !url.includes("presignedUploadedFiles") &&
      !url.includes("simple-storage")
    )
      return;

    abortsArray = abortsArray.filter(elem => {
      const { url: specificUrl } = elem;
      return specificUrl !== url;
    });
  },
  /**
   * Abort all fetch request by type
   * @param type can be single constant or array of constants
   */
  abortAllSignalsByType(type) {
    let types = type;
    if (!Array.isArray(type)) types = [type];

    types.forEach(currentType => {
      abortsArray = abortsArray.filter(elem => {
        const { type: elemType } = elem;
        if (elemType && elemType !== currentType) return true;

        const { abortController } = elem;
        abortController.abort();

        return false;
      });
    });
  },
  /**
   * Abort specific fetch request by it`s url
   * @param url
   * @returns {null|boolean}
   */
  abortSpecificSignal(url) {
    abortsArray = abortsArray.filter(elem => {
      const { url: currentUrl } = elem;
      if (url !== currentUrl) return true;

      const { abortController } = elem;
      abortController.abort();

      return false;
    });
  },
  /**
   * Abort specific fetch request by it`s actionId
   * @param actionId
   * @returns {null|boolean}
   */
  abortSpecificSignalByActionId(actionId) {
    abortsArray = abortsArray.filter(elem => {
      const { actionId: currentId } = elem;
      if (!currentId || actionId !== currentId) return true;

      const { abortController } = elem;
      abortController.abort();

      return false;
    });
  },
  /**
   * Return info about current signals
   * @returns {{type: *, url: *}[]}
   */
  getSignalsInfo() {
    return abortsArray;
  }
};

export default FetchAbortsControl;
