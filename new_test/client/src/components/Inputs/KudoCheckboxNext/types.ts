import { SxProps, Theme } from "@mui/material";
import { CheckboxProps } from "@mui/material";

export type KudoCheckboxProps = {
  /**
   * Background color is highlighted when checked
   * @default false
   */
  bgColorHighlighted?: boolean;
  /**
   * The system prop that allows defining system overrides as well as additional CSS styles.
   */
  checkboxSx?: SxProps<Theme>;
  /**
   * label of component
   */
  label: string;
  /**
   * The position of the label.
   * @default 'end'
   */
  labelPlacement?: "end" | "start" | "top" | "bottom";
  /**
   * If true the label is generated from formatted message
   * @default false
   */
  translateLabel?: boolean;
} & CheckboxProps;
