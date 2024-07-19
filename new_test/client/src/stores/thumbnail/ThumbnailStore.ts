import { makeAutoObservable } from "mobx";
import ThumbnailClass, {
  ThumbnailDataType,
  ThumbnailType
} from "./classes/ThumbnailClass";
import Requests from "../../utils/Requests";
import * as RequestsMethods from "../../constants/appConstants/RequestsMethods";

export class ThumbnailStore {
  private thumbnails: Map<string, ThumbnailClass> = new Map<
    string,
    ThumbnailClass
  >();

  constructor() {
    makeAutoObservable(this);
  }

  createAndGetThumbnail(
    src: string,
    thumbnailData: ThumbnailType
  ): ThumbnailClass {
    let key = "";

    try {
      key = ThumbnailClass.getHashKey(src);

      const thumbnail = this.thumbnails.get(key);
      if (thumbnail) {
        return thumbnail;
      }

      const newThumbnail = new ThumbnailClass(src)
        .withName(thumbnailData.name)
        .withFileId(thumbnailData.fileId)
        .withFallbackElement(thumbnailData.fallbackElement)
        .withClassName(thumbnailData.className)
        .withWidth(thumbnailData.width)
        .withHeight(thumbnailData.height)
        .withDataComponent(thumbnailData.dataComponent)
        .withOnClick(thumbnailData.onClick);

      this.thumbnails.set(key, newThumbnail);

      return newThumbnail;
    } catch (e) {
      return new ThumbnailClass(src).withBrokenURL();
    }
  }

  getThumbnail(src: string): ThumbnailClass | undefined {
    const key = ThumbnailClass.getHashKey(src);

    if (this.thumbnails.has(key)) {
      return this.thumbnails.get(key);
    }

    return undefined;
  }

  hasThumbnail(thumbnail: ThumbnailClass): boolean {
    const key = ThumbnailClass.getHashKey(thumbnail.src);

    try {
      return this.thumbnails.has(key);
    } catch (error) {
      return false;
    }
  }

  addThumbnail(thumbnail: ThumbnailClass): void {
    this.thumbnails.set(ThumbnailClass.getHashKey(thumbnail.src), thumbnail);
  }

  removeThumbnail(thumbnail: ThumbnailClass): void {
    const key = ThumbnailClass.getHashKey(thumbnail.src);

    this.thumbnails.delete(key);
  }

  async fetchThumbnail(src: string): Promise<ThumbnailDataType> {
    const key = ThumbnailClass.getHashKey(src);

    const thumbnail = this.thumbnails.get(key);

    if (thumbnail) {
      try {
        // no need to update as we have same thumbnail stored successfully
        if (
          ThumbnailClass.compareSrc(thumbnail.src, src) &&
          thumbnail.response?.status === 200
        ) {
          return thumbnail.response.data;
        }

        const thumbnailUrl = ThumbnailClass.getThumbnailUrl(src);

        const response = await Requests.sendGenericRequest(
          thumbnailUrl,
          RequestsMethods.GET,
          undefined,
          undefined,
          ["200"]
        );

        // console.log("[Thumbnail]: fetchThumbnail success", response);

        thumbnail.withResponse({
          status: 200,
          data: response.data
        });

        return response.data;
      } catch (error) {
        // console.log("[Thumbnail]: fetchThumbnail error", error);

        thumbnail.withResponse({
          status: 404,
          data: undefined
        });

        throw new Error("loadError");
      }
    } else {
      throw new Error("noThumbnail");
    }
  }
}

const thumbnailStore = new ThumbnailStore();
export default thumbnailStore;
