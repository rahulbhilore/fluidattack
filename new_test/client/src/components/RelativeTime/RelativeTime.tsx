import React, { useEffect, useState, useRef } from "react";
import { selectUnit } from "@formatjs/intl-utils";
import { FormattedRelativeTime } from "react-intl";

/**
 * This component exists only to reproduce the old <FormattedRelative/> behavior
 * See https://github.com/formatjs/react-intl/blob/master/docs/Upgrade-Guide.md#formattedrelativetime
 * for details
 */

export default function RelativeTime({ timestamp }: { timestamp: number }) {
  const timeoutRef = useRef<null | ReturnType<typeof setTimeout>>(null);
  const [config, setConfig] = useState(
    selectUnit(new Date(timestamp || 0), Date.now())
  );

  const calculateUpdateTimeout = () => {
    setConfig(selectUnit(new Date(timestamp || 0), Date.now()));
    const currentDate = Date.now();
    const deltaTime = Math.floor((currentDate - timestamp) / 1000);

    if (deltaTime < 3600) {
      const timeoutId = setTimeout(
        calculateUpdateTimeout,
        deltaTime < 60 ? 1000 : 60000
      );
      timeoutRef.current = timeoutId;
    }
  };

  useEffect(() => {
    calculateUpdateTimeout();
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, [timestamp]);

  return (
    <FormattedRelativeTime
      value={config.value}
      unit={config.unit}
      numeric="auto"
    />
  );
}
