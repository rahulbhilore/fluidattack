import React from "react";
import { makeAutoObservable } from "mobx";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../ApplicationStore";

export type ThumbnailDataType =
  | Blob
  | ArrayBuffer
  | BlobPart[]
  | string
  | undefined;

export type ThumbnailResponseType = {
  data?: ThumbnailDataType;
  status?: number;
};

export type ThumbnailType = {
  src: string;
  name?: string;
  fileId?: string;
  response?: ThumbnailResponseType;
  fallbackElement?: JSX.Element;
  className?: string;
  width?: number;
  height?: number;
  dataComponent?: string;
  onClick?: (
    e: React.MouseEvent<HTMLElement> | React.KeyboardEvent<HTMLElement>
  ) => void;
};

export default class ThumbnailClass implements ThumbnailType {
  src: string;

  name?: string;

  fileId?: string;

  response?: ThumbnailResponseType;

  fallbackElement?: JSX.Element;

  className?: string;

  width?: number;

  height?: number;

  dataComponent?: string;

  isBrokenURL = false;

  onClick?: (
    e: React.MouseEvent<HTMLElement> | React.KeyboardEvent<HTMLElement>
  ) => void;

  constructor(src: string) {
    this.src = src;
    makeAutoObservable(this);
  }

  withName(name?: string): ThumbnailClass {
    this.name = name;
    return this;
  }

  withResponse(response: ThumbnailResponseType): ThumbnailClass {
    this.response = response;
    return this;
  }

  withFileId(fileId?: string): ThumbnailClass {
    this.fileId = fileId;
    return this;
  }

  withFallbackElement(fallbackElement?: JSX.Element): ThumbnailClass {
    this.fallbackElement = fallbackElement;
    return this;
  }

  withClassName(className?: string): ThumbnailClass {
    this.className = className;
    return this;
  }

  withWidth(width?: number): ThumbnailClass {
    this.width = width;
    return this;
  }

  withHeight(height?: number): ThumbnailClass {
    this.height = height;
    return this;
  }

  withDataComponent(dataComponent?: string): ThumbnailClass {
    this.dataComponent = dataComponent;
    return this;
  }

  withOnClick(
    onClick?: (
      e: React.MouseEvent<HTMLElement> | React.KeyboardEvent<HTMLElement>
    ) => void
  ): ThumbnailClass {
    this.onClick = onClick;
    return this;
  }

  withBrokenURL() {
    this.isBrokenURL = true;
    return this;
  }

  static getThumbnailUrl(src: string): string {
    const apiURL = ApplicationStore.getApplicationSetting("apiURL");
    let fullImageUrl = location.origin + src;
    if (apiURL.includes("http")) {
      fullImageUrl = `${src}`;
    }
    return fullImageUrl;
  }

  static getHashKey(src: string): string {
    const thumbnailUrl = ThumbnailClass.getThumbnailUrl(src);
    const urlObj = new URL(thumbnailUrl);
    urlObj.search = "";
    return Math.abs(
      MainFunctions.getStringHashCode(urlObj.toString())
    ).toString();
  }

  static compareSrc(srcOne: string, srcTwo: string): boolean {
    const srcOneObject = new URL(srcOne);
    srcOneObject.search = "";

    const srcTwoObject = new URL(srcTwo);
    srcTwoObject.search = "";

    return (
      srcOneObject.toString().toLowerCase() ===
      srcTwoObject.toString().toLowerCase()
    );
  }

  static formatThroughCanvas(
    data: ThumbnailDataType,
    width?: number,
    height?: number
  ): Promise<string> {
    return new Promise((resolve, reject) => {
      // should not happen
      if (!data) {
        reject(new Error("No data specified"));
      }
      // modify thumbnail canvas
      else {
        const urlCreator = window.URL || window.webkitURL;
        let objectUrl: string;

        if (data instanceof Blob) {
          objectUrl = urlCreator.createObjectURL(data);
        } else if (data instanceof ArrayBuffer) {
          objectUrl = urlCreator.createObjectURL(
            new Blob([new Uint8Array(data)])
          );
        } else if (typeof data === "string") {
          resolve(data);
          return;
        } else {
          objectUrl = urlCreator.createObjectURL(new Blob(data));
        }

        if (!width || !height) {
          resolve(objectUrl);
        } else {
          let canvas: HTMLCanvasElement | null =
            document.createElement("canvas");
          const ctx = canvas.getContext("2d");
          canvas.width = width;
          canvas.height = height;

          const img = new Image();

          img.onload = () => {
            if (ctx && canvas && width && height) {
              let bufferCanvas: HTMLCanvasElement | null =
                document.createElement("canvas");
              const bufferContext = bufferCanvas.getContext("2d");
              if (bufferContext) {
                bufferCanvas.width = img.width;
                bufferCanvas.height = img.height;
                bufferContext.drawImage(img, 0, 0);

                const startX = (img.width - width) / 2;
                const startY = (img.height - height) / 2;

                ctx.drawImage(
                  bufferCanvas,
                  startX,
                  startY,
                  width,
                  height,
                  0,
                  0,
                  width,
                  height
                );
                resolve(canvas.toDataURL());
                canvas = null;
                bufferCanvas = null;
              }
            }
          };
          img.src = objectUrl;
        }
      }
    });
  }
}
