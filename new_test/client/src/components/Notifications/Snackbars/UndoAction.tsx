import React, { forwardRef, useCallback, useEffect } from "react";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import { styled, LinearProgress } from "@mui/material";
import closeIcon from "../../../assets/images/Close.svg";
import useStateRef from "../../../utils/useStateRef";

const StyledDivBlock = styled("div")(({ theme }) => ({
  display: "flex",
  justifyContent: "space-between",
  alignItems: "center",
  marginTop: "5px",
  padding: "12px",
  backgroundColor: theme.palette.FLASH_WHITE,
  width: "25vw",
  color: theme.palette.ONYX,
  fontSize: "12px",
  fontWeight: "bold",
  lineHeight: "20px",
  fontFamily: theme.kudoStyles.FONT_STACK,
  cursor: "pointer",
  [theme.breakpoints.down("sm")]: {
    width: "100%"
  }
}));

const StyledProgressBar = styled(LinearProgress)(() => ({
  height: 9,
  border: `2px solid `,
  "&.MuiLinearProgress-colorPrimary": {
    backgroundColor: "#6892D9"
  },
  "& .MuiLinearProgress-barColorPrimary": {
    backgroundColor: `#124DAF`
  }
}));

const StyledUndoSpan = styled("span")(() => ({
  flex: 1,
  display: "flex",
  justifyContent: "flex-end"
}));

const StyledButton = styled("button")(() => ({
  border: "none",
  backgroundColor: "transparent",
  cursor: "pointer",
  padding: 3,
  marginLeft: 5
}));

const StyledImage = styled("img")(() => ({
  width: "16px"
}));

const StyledUndoButton = styled("button")(({ theme }) => ({
  border: `1px solid ${theme.palette.OBI}`,
  padding: "4px 8px",
  minWidth: 0,
  borderRadius: 5,
  cursor: "pointer",
  backgroundColor: theme.palette.OBI,
  color: "white",
  "&:hover": {
    backgroundColor: theme.palette.CELTIC_BLUE
  }
}));

type Props = {
  id: number;
  undoMessage: string;
  undoFunction: () => void;
  autoHideTime: number;
};

const Snackbar = forwardRef(
  (props: Props, ref: React.ForwardedRef<HTMLDivElement>) => {
    const { closeSnackbar } = useSnackbar();
    const { id, undoMessage, undoFunction, autoHideTime }: Props = props;

    const close = useCallback(() => {
      closeSnackbar(id);
    }, [closeSnackbar]);

    const [timeElapsed, setTimeElapsed, refTimeElapsed] = useStateRef(0);
    useEffect(() => {
      // Here we need to remember that LinearProgress has its default transition set to .4 secs to make changes smooth.
      const maxPercentage = 100 + 40 / autoHideTime;
      const interval = setInterval(() => {
        if (refTimeElapsed.current < maxPercentage) {
          setTimeElapsed(refTimeElapsed.current + 1);
        } else {
          clearInterval(interval);
          close();
        }
      }, 10 * autoHideTime);
      return () => clearInterval(interval);
    }, []);

    let undoActionMessage;

    switch (undoMessage) {
      case "restoreRecentFile": {
        undoActionMessage = "successfullyRemovedFilePreview";
        break;
      }
      default: {
        undoActionMessage = "";
        break;
      }
    }

    const handleUndo = useCallback(
      (e: React.MouseEvent<HTMLButtonElement>) => {
        e.preventDefault();
        undoFunction();
        close();
      },
      [undoFunction, close]
    );

    return (
      <SnackbarContent ref={ref}>
        <div>
          <StyledDivBlock onClick={close}>
            <FormattedMessage id={undoActionMessage} values={{ time: 5 }} />
            <StyledUndoSpan>
              <StyledUndoButton onClick={handleUndo}>
                <FormattedMessage id="undo" />
              </StyledUndoButton>
              <StyledButton>
                <StyledImage src={closeIcon} alt="closeIcon" />
              </StyledButton>
            </StyledUndoSpan>
          </StyledDivBlock>
          <StyledProgressBar
            variant="determinate"
            value={timeElapsed}
            color="primary"
          />
        </div>
      </SnackbarContent>
    );
  }
);

export default Snackbar;
