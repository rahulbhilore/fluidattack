import {
  ButtonBase,
  CircularProgress,
  Menu,
  MenuItem,
  Stack,
  Typography,
  buttonBaseClasses,
  listClasses,
  svgIconClasses
} from "@mui/material";
import React, {
  MouseEvent,
  useContext,
  useEffect,
  useState,
  useRef
} from "react";
import { FormattedMessage } from "react-intl";
import { ReactSVG } from "react-svg";
import ArrowDropDownSVG from "../../../../../assets/images/dialogs/icons/arrowDropDown.svg";
import ArrowDropUpSVG from "../../../../../assets/images/dialogs/icons/arrowDropUp.svg";
import { PermissionsDialogContext } from "../PermissionsDialogContext";
import { PermissionRole } from "../types";
import PermissionsPopover from "./PermissionsPopover";

type Props = {
  isProgressing?: boolean;
  permissionRole: PermissionRole;
  onRoleSelected: (selectedRole: PermissionRole) => void;
};
export default function PermissionRoleMenu({
  isProgressing = false,
  onRoleSelected,
  permissionRole: permissionRoleProp
}: Props) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [openRoleMenu, setOpenRoleMenu] = useState(false);
  const [openPermissionsPopover, setOpenPermissionsPopover] = useState(false);
  const [permissionRole, setPermissionRole] = useState(permissionRoleProp);
  const [selectedPermissionRole, setSelectedPermissionRole] =
    useState<PermissionRole | null>(null);

  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const {
    invitePeople: { availableRoles }
  } = useContext(PermissionsDialogContext);

  const handleCloseMenu = () => {
    setOpenRoleMenu(false);
  };

  const handleOpenMenu = (e: MouseEvent<HTMLButtonElement>) => {
    // BD: Timeouted because focus may change from autocomplete input to dropdown menu
    // When focus change mobile keyboard may be closed and dropdown menu position may change
    setTimeout(() => {
      setOpenRoleMenu(true);
    }, 100);
  };

  const onRoleClick = (role: PermissionRole) => {
    onRoleSelected(role);
    setPermissionRole(role);
    handleCloseMenu();
  };

  const handleClosePermissionsPopover = () => {
    setOpenRoleMenu(false);
    setOpenPermissionsPopover(false);
    setSelectedPermissionRole(null);
  };

  const handleReturnRoleMenu = () => {
    setOpenRoleMenu(true);
    setOpenPermissionsPopover(false);
  };

  useEffect(() => {
    setPermissionRole(permissionRoleProp);
  }, [permissionRoleProp]);

  useEffect(() => {
    setAnchorEl(buttonRef.current);
  }, []);

  return (
    <>
      <ButtonBase
        onClick={handleOpenMenu}
        data-component="sharing-options"
        ref={buttonRef}
      >
        <Stack direction="row" alignItems="center">
          <Typography
            sx={{
              color: theme =>
                `${theme.palette.textField.value.placeholder} !important`
            }}
          >
            {isProgressing ? (
              <CircularProgress size="12px" />
            ) : (
              <FormattedMessage id={permissionRole} />
            )}
          </Typography>
          <ReactSVG
            src={openRoleMenu ? ArrowDropUpSVG : ArrowDropDownSVG}
            style={{ marginLeft: "12px" }}
          />
        </Stack>
      </ButtonBase>
      <Menu
        id="permission-role-menu"
        open={openRoleMenu}
        anchorEl={anchorEl}
        onClose={handleCloseMenu}
        MenuListProps={{
          "aria-labelledby": "basic-button",
          // @ts-ignore Required for tests
          "data-component": "sharing-options-menu"
        }}
        sx={{
          [`& .${listClasses.root}`]: {
            padding: 0
          },
          [`& .${buttonBaseClasses.root}`]: {
            fontSize: "12px",
            color: theme => theme.palette.textField.value.filled,
            padding: "6px 16px"
          },
          [`& .${buttonBaseClasses.root}:hover`]: {
            background: theme => theme.palette.greyCool[34],
            color: theme => theme.palette.LIGHT
          },
          [`& .${svgIconClasses.root}`]: {
            fontSize: "18px"
          },
          [`& .${buttonBaseClasses.root}:hover .${svgIconClasses.root}`]: {
            fontSize: "18px",
            color: theme => theme.palette.LIGHT
          }
        }}
      >
        {availableRoles.map(role => (
          <MenuItem
            key={role}
            onClick={() => onRoleClick(role)}
            data-role={role}
          >
            <Stack
              direction="row"
              justifyContent="space-between"
              // width="120px"
              alignItems="center"
            >
              <FormattedMessage id={role} />
              {/* <IconButton
                sx={{ p: "0 !important" }}
                onClick={e => handleOpenPermissionsPopover(e, role)}
              >
                <ReactSVG
                  src={TuneSVG}
                  style={{ color: "white" }}
                  color="white"
                />
              </IconButton> */}
            </Stack>
          </MenuItem>
        ))}
      </Menu>
      <PermissionsPopover
        anchorEl={anchorEl}
        onClose={handleClosePermissionsPopover}
        onReturn={handleReturnRoleMenu}
        open={openPermissionsPopover}
        permissionRole={selectedPermissionRole}
      />
    </>
  );
}
