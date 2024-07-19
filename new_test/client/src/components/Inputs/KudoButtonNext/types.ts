import { ButtonProps } from "@mui/material";

export type ButtonSize = "small" | "medium" | "large";

export type KudoButtonPropType = {
  /**
   * Data Component
   */
  dataComponent?: string;
  /**
   * If `true`, the component looks like loading.
   * @default false
   */
  loading?: boolean;
  /**
   * The label that will be showed when component is loading.
   * @default 'loading'
   */
  loadingLabelId?: string;
} & Omit<ButtonProps, "variant">;
