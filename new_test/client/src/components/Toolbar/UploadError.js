export default class UploadError extends Error {
  constructor({ code, message, name, objectName }) {
    super(message);
    this.code = code;
    this.message = message;
    this.name = name;
    this.objectName = objectName;
  }
}
