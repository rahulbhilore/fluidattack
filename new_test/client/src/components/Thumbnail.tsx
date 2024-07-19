import React, { useEffect, useState } from "react";
import clsx from "clsx";
import { observer } from "mobx-react-lite";

import drawingSVG from "../assets/images/icons/drawing.svg";
import ThumbnailClass, {
  ThumbnailType
} from "../stores/thumbnail/classes/ThumbnailClass";
import ThumbnailStore from "../stores/thumbnail/ThumbnailStore";
import WebsocketStore, { NEW_THUMBNAIL } from "../stores/WebsocketStore";
import MainFunctions from "../libraries/MainFunctions";
import useStateRef from "../utils/useStateRef";

function Thumbnail({
  src,
  name,
  fileId,
  response,
  fallbackElement,
  className,
  width,
  height,
  dataComponent,
  onClick
}: ThumbnailType) {
  const [srcState, setSrcState] = useState<string>(src);
  const [imageData, setImageData] = useState<string | undefined>(undefined);
  const [thumbnail, setThumbnail, reffedThumbnail] = useStateRef<
    ThumbnailClass | undefined
  >(undefined);

  useEffect(() => {
    setThumbnail(
      ThumbnailStore.createAndGetThumbnail(srcState, {
        src: srcState,
        name,
        fileId,
        response,
        fallbackElement,
        className,
        width,
        height,
        dataComponent,
        onClick
      })
    );
  }, [srcState]);

  const onThumbnailUpdate = ({
    fileId: wsFileId,
    thumbnail: wsSrc
  }: {
    fileId: string;
    thumbnail: string;
  }) => {
    if (
      reffedThumbnail.current &&
      reffedThumbnail.current instanceof ThumbnailClass
    ) {
      const { storageType, objectId: shortId } = MainFunctions.parseObjectId(
        reffedThumbnail.current.fileId
      );

      const isMessageForThisThumbnail =
        wsFileId === reffedThumbnail.current.fileId ||
        wsFileId === shortId ||
        (storageType.length > 0 &&
          (storageType === "OD" || storageType === "ODB") &&
          shortId.toLowerCase().includes(wsFileId.toLowerCase()));

      const shouldThumbnailBeUpdated =
        !ThumbnailClass.compareSrc(reffedThumbnail.current.src, wsSrc) ||
        reffedThumbnail.current.response?.status !== 200 ||
        !reffedThumbnail.current.response.data;

      if (isMessageForThisThumbnail && shouldThumbnailBeUpdated) {
        setSrcState(wsSrc);
      }
    }
  };

  useEffect(() => {
    if (fileId) {
      WebsocketStore.addEventListener(NEW_THUMBNAIL, onThumbnailUpdate);
    }

    return () => {
      if (fileId) {
        WebsocketStore.removeEventListener(NEW_THUMBNAIL, onThumbnailUpdate);
      }
    };
  }, []);

  useEffect(() => {
    if (thumbnail?.isBrokenURL) return;

    ThumbnailStore.fetchThumbnail(srcState).then(data => {
      ThumbnailClass.formatThroughCanvas(data, width, height).then(url =>
        setImageData(url)
      );
    });
  }, [thumbnail]);

  const isGeneric: boolean = thumbnail?.response?.status !== 200 || !imageData;

  if (isGeneric && fallbackElement) {
    return fallbackElement;
  }

  return (
    <img
      alt={name || "Drawing"}
      data-component={dataComponent || "thumbnail"}
      data-name={name || "unknown"}
      className={clsx(isGeneric ? "isGeneric" : "", className || "")}
      src={isGeneric ? drawingSVG : imageData || drawingSVG}
      style={height && width ? { height, width } : {}}
      onClick={onClick}
      onKeyDown={onClick}
    />
  );
}

export default observer(Thumbnail);
