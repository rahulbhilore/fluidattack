import {
  Button,
  Checkbox,
  FormControlLabel,
  FormGroup,
  Grid,
  IconButton,
  Popover,
  Stack,
  SxProps,
  Typography,
  buttonBaseClasses,
  formControlLabelClasses,
  styled,
  svgIconClasses
} from "@mui/material";
import React, { useMemo } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { ReactSVG } from "react-svg";
import arrrowLeftSVG from "../../../../../assets/images/dialogs/icons/arrowLeft.svg";
import { PermissionRole } from "../types";

const StyledIconButton = styled(IconButton)(({ theme: { palette } }) => ({
  borderRadius: 0,
  color: palette.button.secondary.contained.textColor,
  backgroundColor: palette.button.secondary.contained.background.standard,
  width: 26,
  height: 26
}));

type Props = {
  anchorEl: HTMLElement | null;
  onClose: () => void;
  onReturn: () => void;
  open: boolean;
  permissionRole: PermissionRole | null;
};

export default function PermissionsPopover({
  anchorEl,
  onClose,
  onReturn,
  open,
  permissionRole
}: Props) {
  const id = open ? "permissions-list-popover" : undefined;
  const { formatMessage } = useIntl();

  const buttonSx = useMemo<SxProps>(
    () => ({
      height: 28,
      width: 90,
      fontSize: 12,
      padding: 0
    }),
    []
  );

  const permissionList = useMemo(
    () => [
      "Can clone",
      "Can view permissions",
      "Can open",
      "Can comment",
      "Can view public link",
      "Can download"
    ],
    []
  );

  if (!permissionRole) return null;
  return (
    <Popover
      id={id}
      open={open}
      anchorEl={anchorEl}
      onClose={onClose}
      anchorOrigin={{
        vertical: "bottom",
        horizontal: "left"
      }}
      sx={{
        padding: "10px 20px !important"
      }}
    >
      <Grid container padding="10px 20px" rowGap="10px" width={240}>
        <Grid xs={12} item>
          <Stack direction="row" columnGap={1.5} alignItems="center">
            <StyledIconButton
              onClick={onReturn}
              sx={{
                "& .arrow-left-svg div": {
                  display: "flex"
                }
              }}
            >
              <ReactSVG src={arrrowLeftSVG} className="arrow-left-svg" />
            </StyledIconButton>
            <Typography
              fontSize="12px"
              sx={{ color: theme => theme.palette.fg }}
            >
              <FormattedMessage
                id="rolesPermissions"
                values={{ permission: formatMessage({ id: permissionRole }) }}
              />
            </Typography>
          </Stack>
        </Grid>
        <Grid xs={12} item>
          <FormGroup
            sx={{
              pl: 0.5,
              [`& .${svgIconClasses.root}`]: {
                height: 20,
                width: 20
              },
              [`& .${formControlLabelClasses.root}`]: {
                fontSize: 12
              },
              [`& .${buttonBaseClasses.root}`]: {
                py: 0
              }
            }}
          >
            {permissionList.map(item => (
              <FormControlLabel control={<Checkbox />} label={item} />
            ))}
          </FormGroup>
        </Grid>
        <Grid xs={12} item>
          <Stack direction="row" justifyContent="space-between">
            <Button variant="contained" color="secondary" sx={{ ...buttonSx }}>
              <FormattedMessage id="reset" />
            </Button>
            <Button variant="contained" sx={{ ...buttonSx }}>
              <FormattedMessage id="apply" />
            </Button>
          </Stack>
        </Grid>
      </Grid>
    </Popover>
  );
}
