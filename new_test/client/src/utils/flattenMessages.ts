// This interface is using Indexed signature and Recursive type utility in TS.
// It just means our json might have key and value of type string or nested json.
export interface INestedMessages {
  [key: string]: string | INestedMessages;
}
export const flattenMessages = (
  nestedMessages: INestedMessages,
  prefix = ""
): Record<string, string> =>
  Object.keys(nestedMessages || {}).reduce(
    (messages: Record<string, string>, key) => {
      const value = nestedMessages[key];
      const prefixedKey = prefix ? `${prefix}.${key}` : key;
      if (typeof value === "string") {
        messages[prefixedKey] = value;
      } else {
        Object.assign(messages, flattenMessages(value, prefixedKey));
      }

      return messages;
    },
    {}
  );
