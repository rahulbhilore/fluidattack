import { Box, stackClasses } from "@mui/material";
import React, { useContext, useEffect, useRef } from "react";
import MainFunctions from "../../../../../libraries/MainFunctions";
import { PermissionsDialogContext } from "../PermissionsDialogContext";
import PermittedUser from "./PermittedUser";

export default function PermittedUserList({
  emailJustAdded
}: {
  emailJustAdded: string;
}) {
  const { invitePeople } = useContext(PermissionsDialogContext);
  const { objectInfo, collaborators } = invitePeople ?? {};
  const ref = useRef<HTMLDivElement>();
  const containerMaxHeight = 250;
  const perItemHeight = 66;

  useEffect(() => {
    if (containerMaxHeight > perItemHeight * collaborators.length) {
      // no need to scroll
      return;
    }
    const highlightedIndex = collaborators.findIndex(
      item => item.email === emailJustAdded
    );
    if (highlightedIndex > -1) {
      setTimeout(() => {
        ref.current?.scrollTo({
          top: perItemHeight * highlightedIndex,
          left: 0,
          behavior: "smooth"
        });
      }, 100);
    }
  }, [collaborators]);
  return (
    <Box
      ref={ref}
      sx={{
        maxHeight: containerMaxHeight,
        overflowY: "auto",
        [`& > .${stackClasses.root}:nth-child(even)`]: {
          background: theme => theme.palette.greyCool["04"]
        }
      }}
    >
      <PermittedUser
        key={objectInfo?.ownerId ?? MainFunctions.guid()}
        _id={objectInfo?.ownerId}
        userId={objectInfo?.ownerId}
        canModify
        collaboratorRole="owner"
        email={objectInfo?.ownerEmail}
        name={objectInfo?.owner}
        isInherited={false}
      />
      {collaborators.map(({ role, ...params }) => (
        <PermittedUser
          {...params}
          collaboratorRole={role}
          key={params._id}
          highlight={emailJustAdded === params.email}
        />
      ))}
    </Box>
  );
}
