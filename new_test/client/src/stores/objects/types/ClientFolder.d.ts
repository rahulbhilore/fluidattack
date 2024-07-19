import ClientObject from "./ClientObject";

export default interface ClientFolder extends ClientObject {
  type: "folder";
  folderId: string;
}
