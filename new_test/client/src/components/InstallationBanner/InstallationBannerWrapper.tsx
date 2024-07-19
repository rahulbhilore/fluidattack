import { Box, styled } from "@mui/material";
import backgroundImage_ from "../../assets/images/InstallationPage/background.png";

export default styled(Box)(({ theme }) => ({
  position: "fixed",
  top: 0,
  left: 0,
  bottom: 0,
  right: 0,
  zIndex: 2000,
  paddingLeft: "12%",
  paddingRight: "12%",
  backgroundColor: theme.palette.installationBanner.bgColor,
  backgroundImage: `url("${backgroundImage_}")`,
  backgroundSize: "cover",
  overflow: "auto"
}));
