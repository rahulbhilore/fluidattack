import { Skeleton } from "@mui/material";
import React from "react";

type Props = {
  height: React.CSSProperties["height"];
  width?: React.CSSProperties["height"];
};

export default function LinkSkeleton({ height, width }: Props) {
  return (
    <Skeleton
      animation="wave"
      variant="rounded"
      sx={{
        height,
        opacity: 0.4,
        backgroundColor: "#485569",
        ...(width ? { width } : { flexGrow: 1 })
      }}
    />
  );
}
