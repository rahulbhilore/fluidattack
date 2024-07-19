import React from "react";
import { FormattedMessage } from "react-intl";
import {
  styled,
  Typography,
  LinearProgress,
  IconButton,
  Tooltip
} from "@mui/material";
import Close from "@mui/icons-material/Close";

const StyledLinearProgess = styled(LinearProgress)(({ theme }) => ({
  backgroundColor: theme.palette.REY,
  display: "inline-block",
  width: "80%",
  verticalAlign: "middle",
  margin: "30px 0",
  "&.templates": {
    margin: 0
  }
}));

const StyledClose = styled(Close)(({ theme }) => ({
  color: theme.palette.JANGO,
  fontSize: theme.typography.pxToRem(20)
}));

const StyledIconButton = styled(IconButton)(({ theme }) => ({
  padding: theme.spacing(1)
}));

const StyledTypography = styled(Typography)(({ theme }) => ({
  color: theme.palette.JANGO,
  fontSize: theme.typography.pxToRem(12),
  paddingRight: theme.spacing(1),
  display: "inline-block"
}));

type Props = {
  value: number;
  name: string;
  id: string;
  cancelFunction: () => void;
  customClass?: string;
  showLabel?: boolean;
};

export default function UploadProgress({
  value,
  name,
  id,
  cancelFunction,
  customClass,
  showLabel
}: Props) {
  const showProgress = name === "preparing" || name === "uploading";

  return (
    <>
      {showLabel && value ? (
        <StyledTypography variant="body2">{value}%</StyledTypography>
      ) : null}
      {showProgress ? (
        <StyledLinearProgess
          variant={isNaN(value) ? "indeterminate" : "determinate"}
          className={customClass}
          value={isNaN(value) ? 0 : value}
        />
      ) : null}
      {showProgress && id && cancelFunction ? (
        <Tooltip placement="top" title={<FormattedMessage id="cancelUpload" />}>
          <StyledIconButton
            data-component="cancel_upload"
            onClick={cancelFunction}
          >
            <StyledClose />
          </StyledIconButton>
        </Tooltip>
      ) : null}
    </>
  );
}
