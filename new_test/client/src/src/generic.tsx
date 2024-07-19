import "core-js/stable";
import "proxy-polyfill";
import React, { Suspense } from "react";
import { render } from "react-dom";
import "regenerator-runtime/runtime";
import Loader from "../components/Loader";
import RouterOverlay from "../components/Router/RouterOverlay";
import MainFunctions from "../libraries/MainFunctions";
import PageLoad from "../libraries/PageLoad";
import {
  LanguageCode,
  languages,
  normalizeLocaleAndLang
} from "../utils/languages";
import KudoThemeProvider from "./KudoThemeProvider";
import * as serviceWorker from "./serviceWorker";

if (process.env.NODE_ENV !== "production") {
  import("@welldone-software/why-did-you-render").then(whyDidYouRender => {
    whyDidYouRender.default(React, {
      trackAllPureComponents: true
    });
  });
}

if (!Intl.PluralRules) {
  import("@formatjs/intl-pluralrules/polyfill");
  Object.keys(languages).forEach((lang: LanguageCode) => {
    import(/* @vite-ignore */ `@formatjs/intl-pluralrules/locale-data/${lang}`);
  });
}

if (!Intl.RelativeTimeFormat) {
  import("@formatjs/intl-relativetimeformat/polyfill");
  Object.keys(languages).forEach((lang: LanguageCode) => {
    import(
      /* @vite-ignore */ `@formatjs/intl-relativetimeformat/locale-data/${lang}`
    );
  });
}

function runKudo() {
  PageLoad.initPage().then(loadResponse => {
    const { userLanguage, endPage, translations, isEnforced } = loadResponse;
    if (endPage === false) return null;
    const { language, locale } = normalizeLocaleAndLang(userLanguage);
    const container = document.getElementById("react");
    if (!container) return null;
    render(
      <Suspense fallback={<Loader />}>
        <React.StrictMode>
          <KudoThemeProvider>
            <RouterOverlay
              locale={locale}
              language={language}
              messages={translations}
              isEnforced={isEnforced}
            />
          </KudoThemeProvider>
        </React.StrictMode>
      </Suspense>,
      container
    );
    return true;
  });
}

if (MainFunctions.checkBrowser().status === true) {
  runKudo();
}

serviceWorker.unregister();
