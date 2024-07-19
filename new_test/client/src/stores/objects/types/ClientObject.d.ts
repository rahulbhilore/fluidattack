import ObjectActions from "./ObjectActions";

export default interface ClientObject {
  id: string;
  _id: string;
  fullId: string;
  storage: string;
  accountId: string;
  name: string;
  externalType: string;
  mimeType: string;
  isShortcut: boolean;

  full: boolean;
  updateHash: number;
  actions: ObjectActions;
  type: string;
}
