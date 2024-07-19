import { Box, Stack, useMediaQuery, useTheme } from "@mui/material";
import React, { ReactNode, useCallback, useEffect, useState } from "react";
import { useIntl } from "react-intl";
import UserInfoStore, { INFO_UPDATE } from "../../../../stores/UserInfoStore";

type AccountInfoPropType = {
  id: string;
  userStoreKey?: string;
  renderValue?: ReactNode;
};

export default function AccountInfoItem({
  id,
  renderValue = "",
  userStoreKey
}: AccountInfoPropType) {
  const { formatMessage } = useIntl();
  const [storeValue, setStoreValue] = useState(
    UserInfoStore.getUserInfo(userStoreKey)
  );
  const theme = useTheme();
  const isMediumScreen = useMediaQuery(theme.breakpoints.down("md"));

  const updateStoreValue = useCallback(() => {
    setStoreValue(UserInfoStore.getUserInfo(userStoreKey));
  }, [UserInfoStore]);

  useEffect(() => {
    UserInfoStore.addListener(INFO_UPDATE, updateStoreValue);

    return () => {
      UserInfoStore.removeListener(INFO_UPDATE, updateStoreValue);
    };
  }, []);

  return (
    <Stack
      py={2.5}
      px={2}
      borderRadius={1}
      fontFamily={'"Roboto","Helvetica","Arial",sans-serif'}
      direction={isMediumScreen ? "column" : "row"}
      columnGap={1}
    >
      <Box flex={1}>{`${formatMessage({ id })}:`}</Box>
      <Box flex={1} pl={!isMediumScreen ? 2 : 0}>
        {userStoreKey ? storeValue : renderValue}
      </Box>
    </Stack>
  );
}
