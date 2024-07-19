import Storage from "./Storage";

export default class UtmTracker {
  static newURLSearch = "";

  static utmsObject: Record<string, string> = {};

  static updateLinks = () => {
    document.querySelectorAll("a[href]").forEach((link: HTMLAnchorElement) => {
      const current = link.href;
      if (
        !current.includes(document.location.origin) &&
        current.includes("http") &&
        !current.includes(UtmTracker.newURLSearch)
      ) {
        const splitByHash = current.split("#");
        link.href =
          splitByHash[0] +
          (splitByHash[0].includes("?") ? "&" : "?") +
          UtmTracker.newURLSearch +
          (splitByHash[1] ? `#${splitByHash[1]}` : "");
      }
    });
  };

  static updateLink = (link: string) => {
    if (
      !link.includes(document.location.origin) &&
      link.includes("http") &&
      !link.includes(UtmTracker.newURLSearch)
    ) {
      return link + (link.includes("?") ? "&" : "?") + UtmTracker.newURLSearch;
    }
    return link;
  };

  static updateInfoFromUserObject = (utms: Record<string, string>) => {
    UtmTracker.newURLSearch = "";
    const searchArray: Array<string> = [];
    const utmsArray = [
      "utm_source",
      "utm_campaign",
      "utm_medium",
      "utm_content",
      "utm_term"
    ];
    Object.entries(utms).forEach(([utmKey, utmValue]) => {
      // for some reason in CP utms are like this
      // "utmContent", "utmMedium" etc.
      // We need to convert it to normal values
      // "utm_content" like
      let convertedKey = utmKey;
      if (!utmsArray.includes(utmKey)) {
        convertedKey = `utm_${utmKey.substr("utm".length).toLowerCase()}`;
      }
      UtmTracker.utmsObject[convertedKey] = utmValue;
      searchArray.push(`${convertedKey}=${utmValue}`);
      Storage.setItem(convertedKey, utmValue, true);
    });
    UtmTracker.newURLSearch = searchArray.join("&");
    UtmTracker.updateLinks();
  };

  static initializeTracker = () => {
    const splitAndFilter = (arr: Array<string>) =>
      arr.map(v => v.split("=")).filter(v => v[0].includes("utm_"));
    const joinIntoURL = (arr: Array<Array<string>>) =>
      arr.map(v => `${v[0].trim()}=${v[1].trim()}`).join("&");
    const { search } = document.location;
    const urlParams = splitAndFilter(search.slice(1).split("&"));
    let newURLSearch = "";
    if (urlParams.length === 0) {
      const { cookie } = document;
      newURLSearch = joinIntoURL(splitAndFilter(cookie.split(";")));
    } else {
      urlParams.forEach(v => Storage.setItem(v[0], v[1], true));
      newURLSearch = joinIntoURL(urlParams);
    }
    if (newURLSearch.length > 0) {
      UtmTracker.newURLSearch = newURLSearch;
      if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", UtmTracker.updateLinks);
      } else {
        UtmTracker.updateLinks();
      }
    }
  };
}
