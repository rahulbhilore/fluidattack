import { SwitchProps, SxProps, Theme } from "@mui/material";

export type SpecialDimensions = {
  /**
   * Component height as pixel
   * @default 58
   */
  height: number;
  /**
   * PaddingX of component, the offset of thumb to component's edges as pixel
   * @default 8
   */
  paddingX: number;
  /**
   * Thumb height as pixel
   * @default 21
   */
  thumbHeight: number;
  /**
   * Thumb width as pixel
   * @default 21
   */
  thumbWidth: number;
  /**
   * Component width as pixel
   * @default 31
   */
  width: number;
};

export type KudoSwitchProps = {
  /**
   * Data component
   */
  dataComponent?: string;
  /**
   * If `true`, the component will take up the full width of its container.
   * @default false
   */
  fullWidth?: boolean;
  /**
   * label of component
   */
  label?: string;
  /**
   * The position of the label.
   * @default 'end'
   */
  labelPlacement?: "end" | "start" | "top" | "bottom";
  specialDimensions?: SpecialDimensions;
  /**
   * The system prop that allows defining system overrides as well as additional CSS styles.
   */
  switchSx?: SxProps<Theme>;
  /**
   * If true the label is generated from formatted message
   * @default false
   */
  translateLabel?: boolean;
  /**
   * Custom component to render instead of label
   */
  customLabelComponent?: React.ReactNode;
} & Omit<SwitchProps, "size">;
