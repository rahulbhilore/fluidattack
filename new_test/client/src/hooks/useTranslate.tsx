import type { PrimitiveType } from "react-intl";
import { useIntl } from "react-intl";
import type { FormatXMLElementFn } from "intl-messageformat";
import { useCallback } from "react";
import { TranslationKey } from "../libraries/PageLoad";

const useTranslate = () => {
  const { formatMessage } = useIntl();
  // Define a function called t that takes in a key of type TranslationKey
  const t = useCallback(
    (
      key: TranslationKey,
      values?: Record<
        string,
        PrimitiveType | FormatXMLElementFn<string, string>
      >
    ) =>
      // Call formatMessage with an object that has an id property set to the given key
      formatMessage({ id: key }, values),
    [formatMessage]
  );

  return { t };
};

export default useTranslate;
