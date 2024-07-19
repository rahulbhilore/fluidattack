export default class SessionError extends Error {
  public code: number;

  public editSessionId: string;

  constructor(message: string, code: number, editSessionId: string) {
    super(message);
    this.code = code;
    this.editSessionId = editSessionId;
  }
}
