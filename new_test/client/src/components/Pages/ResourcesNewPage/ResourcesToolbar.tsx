import React, { useRef } from "react";
import { observer } from "mobx-react-lite";
import { FormattedMessage } from "react-intl";
import { styled } from "@mui/styles";
import Box from "@mui/material/Box";
import FileDragAndDrop from "../../Toolbar/FileDragAndDrop";
import TooltipButton from "../../Toolbar/TooltipButton";
import BaseResourcesStore from "../../../stores/resources/BaseResourcesStore";

import uploadSVG from "../../../assets/images/upload.svg";
import newFolderSVG from "../../../assets/images/newfolder.svg";

const StyledInput = styled("input")({
  display: "none"
});

type Props = {
  storage: BaseResourcesStore;
  dropHandler: (event: React.DragEvent<HTMLInputElement>) => void;
  folderCreateHandler: () => void;
  uploadButtonClickHandler: (
    event: React.SyntheticEvent,
    input: HTMLInputElement | null
  ) => void;
  uploadFileHandler: (event: React.FormEvent<HTMLInputElement>) => void;
  createFolderMessage: string;
  uploadFileMessage: string;
};

function ResourcesToolbar({
  storage,
  dropHandler,
  folderCreateHandler,
  uploadFileHandler,
  uploadButtonClickHandler,
  createFolderMessage,
  uploadFileMessage
}: Props) {
  const inputRef = useRef<HTMLInputElement | null>(null);

  const proxyButtonClickHandler = (event: React.SyntheticEvent) => {
    uploadButtonClickHandler(event, inputRef.current);
  };

  return (
    <Box>
      <FileDragAndDrop dropHandler={dropHandler} />
      {storage.canSubFolderBeCreated() ? (
        <TooltipButton
          disabled={false}
          onClick={folderCreateHandler}
          tooltipTitle={<FormattedMessage id={createFolderMessage} />}
          icon={newFolderSVG}
          id="createFolderButton"
          dataComponent="createFolderButton"
        />
      ) : null}
      <StyledInput
        name="file"
        type="file"
        onChange={uploadFileHandler}
        ref={inputRef}
        multiple
      />
      <TooltipButton
        disabled={false}
        onClick={proxyButtonClickHandler}
        tooltipTitle={<FormattedMessage id={uploadFileMessage} />}
        icon={uploadSVG}
        id="uploadFileButton"
        dataComponent="uploadFileButton"
      />
    </Box>
  );
}

export default observer(ResourcesToolbar);
