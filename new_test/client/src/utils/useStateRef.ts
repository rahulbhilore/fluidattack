import {
  Dispatch,
  MutableRefObject,
  SetStateAction,
  useEffect,
  useRef,
  useState
} from "react";

export default function useStateRef<T>(
  defVal: T
): [T, Dispatch<SetStateAction<T>>, MutableRefObject<T>] {
  const [val, setVal] = useState<T>(defVal);
  const valRef = useRef<T>(val);

  useEffect(() => {
    valRef.current = val;
  }, [val]);

  return [val, setVal, valRef];
}
