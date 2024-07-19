/* eslint-disable */
import { queryHelpers } from "@testing-library/dom";

export const queryByDataComponent = queryHelpers.queryByAttribute.bind(
  null,
  "data-component"
);

export const queryAllByDataComponent = queryHelpers.queryAllByAttribute.bind(
  null,
  "data-component"
);

/* eslint-enable */
