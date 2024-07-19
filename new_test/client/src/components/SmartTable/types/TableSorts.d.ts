/* eslint-disable @typescript-eslint/no-explicit-any */
import Immutable from "immutable";

export default interface TableSorts<T> {
  [type: string]: (
    a: Immutable.Map<string, T>,
    b: Immutable.Map<string, T>
  ) => number;
}
