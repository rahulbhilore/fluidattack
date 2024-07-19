import _ from "underscore";

export const languages = {
  en: "en",
  ja: "ja",
  ko: "ko",
  pl: "pl",
  de: "de",
  // es: "es",
  cn: "cn",
  // fr: "fr",
  // it: "it",
  // pt: "pt",
  // ru: "ru",
  // tr: "tr",
  zh: "zh"
} as const;

export type LanguageCode = keyof typeof languages;

// all must be lower-case as we check it in lower-case
export const languagesToLocales = {
  en: "en-gb",
  cn: "zh-hans",
  de: "de-de",
  ja: "ja-jp",
  ko: "ko",
  pl: "pl",
  zh: "zh-hant"
} as const;

export type Locale = (typeof languagesToLocales)[LanguageCode];
export const localesToLanguages: Record<Locale, LanguageCode> =
  _.invert(languagesToLocales);
export const defaultLocale: Locale = languagesToLocales.en;
export const defaultLanguage: LanguageCode = localesToLanguages[defaultLocale];

export const normalizeLocaleAndLang = (
  langOrLocale: string
): { locale: Locale; language: LanguageCode } => {
  try {
    // locale should be lowerCased and if it's undefined - set to en_gb
    let formalizedLocale = (langOrLocale || defaultLocale).toLowerCase();
    if (formalizedLocale.includes("_")) {
      // for older type
      formalizedLocale = formalizedLocale.replace("_", "-");
    }
    let locale: Locale = defaultLocale;
    let language: LanguageCode = defaultLanguage;
    const isLocale = Object.values(languagesToLocales).some(
      val => val === formalizedLocale
    );
    if (isLocale) {
      locale = formalizedLocale as Locale;
      // find language by this locale
      language = localesToLanguages[locale];
    } else if (Object.keys(languagesToLocales).includes(formalizedLocale)) {
      // check that this is a language
      language = formalizedLocale as LanguageCode;
      locale = languagesToLocales[language];
    }
    // otherwise default values are returned
    return { locale, language };
  } catch (ex) {
    return { locale: defaultLocale, language: defaultLanguage };
  }
};
