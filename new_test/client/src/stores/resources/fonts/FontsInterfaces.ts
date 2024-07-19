import { ResourceFileInterface } from "../BaseInterfases";

export interface FontFacesInterface {
  fontFamily: string | undefined,
  index: number | undefined,
  weight: number | undefined,
  style: string | undefined,
  bold: boolean | undefined,
  italic: boolean | undefined,
}

export interface FontFileInterface extends ResourceFileInterface { 
  faces: FontFacesInterface | undefined;
}
