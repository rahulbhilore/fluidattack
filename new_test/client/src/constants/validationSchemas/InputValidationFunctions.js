/* eslint-disable */
// TODO: fix eslint error and re-enable eslint
export const isEmail = value =>
  value.match(
    /^((([a-z]|\d|[!#\$%&'\*\+\-\/=\?\^_`{\|}~]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])+(\.([a-z]|\d|[!#\$%&'\*\+\-\/=\?\^_`{\|}~]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])+)*)|((\x22)((((\x20|\x09)*(\x0d\x0a))?(\x20|\x09)+)?(([\x01-\x08\x0b\x0c\x0e-\x1f\x7f]|\x21|[\x23-\x5b]|[\x5d-\x7e]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(\\([\x01-\x09\x0b\x0c\x0d-\x7f]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF]))))*(((\x20|\x09)*(\x0d\x0a))?(\x20|\x09)+)?(\x22)))@((([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])*([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])))\.)+(([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])*([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])))$/i
  ) !== null;
/* eslint-enable */
export const isNumeric = value => value.match(/^-?[0-9]+$/) !== null;
export const isAlpha = value => value.match(/^[a-zA-Z ]+$/) !== null;
export const isPassword = value => value.match(/^.{0,}$/) !== null;
export const isURL = value => value.match(/^https?:\/\//) !== null;
export const isLength = (value, min, max) => {
  if (max !== undefined) {
    return value.length >= min && value.length <= max;
  }
  return value.length >= min;
};
export const isNonEmpty = value => (value || "").trim().length > 0;
export const isntFactuallyEmpty = value => value.match(/^[( |.)]*$/) == null;
export const any = () => true;

const storageValidationFunctions = {
  dropbox: value => value.match(/^[^":\\/|?*<>]+$/) !== null,
  box: value => value.match(/^[^\\/]+$/) !== null,
  webdav: value => value.match(/^[^":\\/|?#'*<>]+$/) !== null,
  onedrive: value => value.match(/^[^":\\/|?*<>]*[^:"\\/|?*<>.]$/) !== null,
  onedrivebusiness: value =>
    value.match(/^[^":\\/|?*<>]*[^:"\\/|?*<>.]$/) !== null,
  sharepoint: value => value.match(/^[^":\\/|?*<>]*[^:"\\/|?*<>.]$/) !== null,
  trimble: value => value.match(/^[^":\\/|?*<>]*[^:"\\/|?*<>.]$/) !== null
};

export const getNameValidationFunction = storageType => {
  const baseValidation = f => isNonEmpty(f) && isntFactuallyEmpty(f);
  if (
    storageType &&
    Object.prototype.hasOwnProperty.call(
      storageValidationFunctions,
      storageType
    )
  ) {
    return f => baseValidation(f) && storageValidationFunctions[storageType](f);
  }
  return baseValidation;
};
