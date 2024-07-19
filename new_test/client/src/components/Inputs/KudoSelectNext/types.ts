import { SelectProps } from "@mui/material";

export type KudoSelectProps = {
  /**
   * data component
   */
  dataComponent?: string;
  /**
   * Default value
   */
  defaultValue?: string;
  /**
   * Represents id of FormattedMessage
   */
  label: string;
  /**
   * Dropdown options
   */
  options: Record<string, string>;
  /**
   * Option keys are sorted ascending or descending
   * @default false
   */
  sort?: boolean;
} & SelectProps<string>;
