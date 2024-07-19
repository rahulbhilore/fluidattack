import { RadioGroupProps } from "@mui/material";

export type KudoRadioProps = {
  /**
   * Data component value
   */
  dataComponent?: string;
  /**
   * If `true` all radio is disabled
   * @default false
   */
  disabled?: boolean;
  /**
   * Contains all radio option values to be disabled
   */
  disabledOptions?: string[];
  /**
   * Label for radio group
   */
  label: string;
  /**
   * Options will be passed to each radio input
   */
  options: Array<{ label: string; value: string }>;
} & RadioGroupProps;
