import React, { forwardRef } from "react";
import clsx from "clsx";
import makeStyles from "@material-ui/core/styles/makeStyles";
import DialogContent from "@material-ui/core/DialogContent";

const useStyles = makeStyles(theme => ({
  root: {
    padding: theme.spacing(2),
    // @ts-ignore
    color: theme.palette.DARK,
    fontSize: ".8rem"
  }
}));

type DialogBodyProps = {
  children: React.ReactNode;
  className?: string;
  stopScroll?: () => boolean;
};

let height = 0;
export const DEFAULT_SCROLL_TIME = 50;

const DialogBody = forwardRef(
  ({ children, className, stopScroll }: DialogBodyProps, ref) => {
    const classes = useStyles();

    const onScroll = (e: React.UIEvent<HTMLDivElement, UIEvent>) => {
      if (!stopScroll) return;
      setTimeout(() => {
        height = (e.target as HTMLDivElement).scrollTop;
      }, DEFAULT_SCROLL_TIME);

      if (stopScroll && !stopScroll()) return;

      (e.target as HTMLDivElement).scrollTo({
        top: height,
        left: 0,
        behavior: "instant"
      });
    };

    return (
      <DialogContent
        className={clsx(classes.root, className)}
        ref={ref}
        onScroll={onScroll}
      >
        {children}
      </DialogContent>
    );
  }
);
export default DialogBody;
