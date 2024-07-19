import ClientObject from "./ClientObject";

export default interface ClientFile extends ClientObject {
  type: "file";
  parent: string;

  size: string;
  sizeValue: number;
  updateDate: number;

  preview: string;
  previewId: string;
  thumbnail: string;
}
