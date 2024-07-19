import React, { useCallback, useEffect, useState } from "react";
import { Box } from "@mui/material";
import LinkSkeleton from "./LinkSkeleton";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import drawingSVG from "../../../assets/images/icons/drawing.svg";

type Props = {
  isLoading: boolean;
  thumbnail: string;
  preview: string;
  aspect: "vertical" | "horizontal";
};

const USE_PREVIEW = false;

export default function LinkThumbnail({
  isLoading,
  thumbnail,
  preview,
  aspect
}: Props) {
  const [fetchingImage, setFetchingImage] = useState<boolean>(false);
  const [data, setData] = useState<"loadError" | string>("loadError");

  const fetchImage = useCallback(async () => {
    const fetchImageByUrl = async (url: string) => {
      const { data: imageData } = await Requests.sendGenericRequest(
        url,
        RequestsMethods.GET,
        undefined,
        undefined,
        ["200"]
      );

      return imageData;
    };

    if (!isLoading && !fetchingImage) {
      setFetchingImage(true);
      const urlCreator = window.URL || window.webkitURL;

      try {
        if (!USE_PREVIEW) {
          throw new Error();
        }
        setData(
          urlCreator.createObjectURL(
            new Blob([new Uint8Array(await fetchImageByUrl(preview))])
          )
        );
        setFetchingImage(false);
      } catch (e1) {
        try {
          setData(
            urlCreator.createObjectURL(
              new Blob([new Uint8Array(await fetchImageByUrl(thumbnail))])
            )
          );
          setFetchingImage(false);
        } catch (e2) {
          setData("loadError");
          setFetchingImage(false);
        }
      }
    }
  }, [
    data,
    setData,
    thumbnail,
    preview,
    fetchingImage,
    setFetchingImage,
    isLoading
  ]);

  useEffect(() => {
    if (!isLoading) {
      fetchImage();
    }
  }, [isLoading]);

  if (isLoading || fetchingImage) {
    return <LinkSkeleton height="100%" width="100%" />;
  }

  return (
    <Box
      sx={{
        width: "100%",
        height: "100%",
        backgroundColor: "#3C4553",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        overflow: "hidden",
        borderRadius: "6px"
      }}
    >
      <img
        style={{
          maxHeight: data === "loadError" ? "80%" : "max-content",
          width: aspect === "horizontal" ? "100%" : "auto",
          height: aspect === "horizontal" ? "auto" : "100%"
        }}
        src={data === "loadError" ? drawingSVG : data}
        alt="preview"
      />
    </Box>
  );
}
