import { Box, Button, Grid, Stack, Typography, styled } from "@mui/material";
import browser from "browser-detect";
import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useState
} from "react";
import { useIntl } from "react-intl";
import aresTouchLogo from "../../assets/images/InstallationPage/aresTouchLogo.png";
import MainFunctions from "../../libraries/MainFunctions";
import Storage from "../../utils/Storage";
import KudoButton from "../Inputs/KudoButtonNext/KudoButton";
import InstallationBannerWrapper from "./InstallationBannerWrapper";
import FilesListStore, {
  CURRENT_FILE_INFO_UPDATED
} from "../../stores/FilesListStore";

const StyledSurface = styled(Box)(({ theme }) => ({
  alignItems: "center",
  backgroundColor: theme.palette.drawer.bg,
  borderRadius: 8,
  display: "flex",
  flexDirection: "column",
  justifyContent: "center",
  padding: theme.spacing(3),
  width: "100%"
}));

export default function InstallationBanner() {
  const [showPage, setShowPage] = useState(false);
  const isMobile = MainFunctions.isMobileDevice();
  const storageItemKey = "showInstallationBannerStatus";
  const [isUniversalBanner] = useState(true);
  const { formatMessage } = useIntl();
  const searchParams = new URLSearchParams(location.search);
  const [deviceHeightLargerThanSurface, setDeviceHeightLargerThanSurface] =
    useState(false);
  const wrapperVerticalPadding = 32;

  const onStorageChanged = useCallback(() => {
    const skipStatus = Storage.getItem(storageItemKey);
    if (skipStatus === "show") setShowPage(true);
    else if (skipStatus === "hide") setShowPage(false);
    else {
      Storage.setItem(storageItemKey, "show");
      window.dispatchEvent(new Event("storage"));
    }
  }, [storageItemKey]);

  const handleClickSkip = useCallback(() => {
    Storage.setItem(storageItemKey, "hide");
    window.dispatchEvent(new Event("storage"));
  }, [storageItemKey]);

  const handleClickGetAresTouch = useCallback(() => {
    const appStoreLink =
      "https://apps.apple.com/app/ares-touch-dwg-viewer-cad/id988848336";
    const playStoreLink =
      "https://play.google.com/store/apps/details?id=com.graebert.aresbeta";

    const { os } = browser();
    const isAndroid = /android/i.test(os as string);
    location.href = isAndroid ? playStoreLink : appStoreLink;
  }, []);

  useEffect(() => {
    window.addEventListener("storage", onStorageChanged);
    onStorageChanged();
    return () => {
      window.removeEventListener("storage", onStorageChanged);
    };
  }, []);

  useLayoutEffect(() => {
    let observer: unknown = null;
    setTimeout(() => {
      const surfaceElement = document.getElementById("suggestion-page-surface");
      observer = new ResizeObserver(() => {
        if (surfaceElement)
          setDeviceHeightLargerThanSurface(
            window.innerHeight >
              wrapperVerticalPadding * 2 + surfaceElement.offsetHeight
          );
      });
      if (surfaceElement) (observer as ResizeObserver).observe(surfaceElement);
    }, 100);

    return () => {
      const surfaceElement = document.getElementById("suggestion-page-surface");
      if (surfaceElement && observer)
        (observer as ResizeObserver)?.unobserve?.(surfaceElement);
    };
  }, []);

  // DK: we only care about this here
  const [hasDirectAccess, setHasDirectAccess] = useState<boolean>(false);

  const updateFileInfo = useCallback(() => {
    setHasDirectAccess({ ...FilesListStore.getCurrentFile() }.hasDirectAccess);
  }, []);

  useEffect(() => {
    if (searchParams.has("token")) {
      FilesListStore.addEventListener(
        CURRENT_FILE_INFO_UPDATED,
        updateFileInfo
      );
    }
    return () => {
      FilesListStore.removeEventListener(
        CURRENT_FILE_INFO_UPDATED,
        updateFileInfo
      );
    };
  }, [searchParams]);

  // Commander/Touch integration
  if (location.pathname.startsWith("/app/")) return null;
  if (location.pathname.startsWith("/notify")) return null;
  if (searchParams.has("token") && !hasDirectAccess) return null;
  if (!isMobile) return null;
  if (!showPage) return null;

  return (
    <InstallationBannerWrapper
      sx={{
        py: `${wrapperVerticalPadding}px`,
        ...(deviceHeightLargerThanSurface
          ? { display: "flex", alignItems: "center" }
          : {})
      }}
    >
      <StyledSurface
        id="suggestion-page-surface"
        sx={{ rowGap: theme => theme.spacing(isUniversalBanner ? 4.5 : 2.5) }}
      >
        <img src={aresTouchLogo} alt="Ares Touch Logo" />

        <Stack rowGap={2}>
          <Typography
            fontSize={16}
            fontWeight={400}
            textAlign="center"
            variant="h1"
          >
            {formatMessage({
              id: isUniversalBanner
                ? "knowGraebertAlsoOffersMobileCADSolutions"
                : "youAreAboutViewASharingLink"
            })}
          </Typography>

          <Typography fontWeight={400} textAlign="center" variant="body2">
            {formatMessage({
              id: isUniversalBanner
                ? "takeAdvantageOfYourMobileDeviceAndWorkWithAresTouch"
                : "takeAdvantageOfYourMobileDeviceAndSeeOverAresTouch"
            })}
          </Typography>

          {!isUniversalBanner && (
            <Typography variant="body2" fontWeight={400} textAlign="center">
              {formatMessage({ id: "onyl2StepsToStart" })}
            </Typography>
          )}
        </Stack>
        {isUniversalBanner ? (
          <Grid columnSpacing={2} container rowGap={1}>
            <Grid item fontSize={18} textAlign="right" xs={4}>
              {formatMessage({ id: "simple" })}
            </Grid>
            <Grid
              alignItems="center"
              alignSelf="center"
              sx={{
                color: theme => theme.palette.installationBanner.stepDetailColor
              }}
              item
              xs={8}
            >
              {formatMessage({ id: "openSharingLinksOrCloudFiles" })}
            </Grid>
            <Grid fontSize={18} item textAlign="right" xs={4}>
              {formatMessage({ id: "explore" })}
            </Grid>
            <Grid
              alignItems="center"
              alignSelf="center"
              sx={{
                color: theme => theme.palette.installationBanner.stepDetailColor
              }}
              item
              xs={8}
            >
              {formatMessage({ id: "powerfulSetOfCADfeatures" })}
            </Grid>
            <Grid fontSize={18} item textAlign="right" xs={4}>
              {formatMessage({ id: "easy" })}
            </Grid>
            <Grid
              alignItems="center"
              alignSelf="center"
              sx={{
                color: theme => theme.palette.installationBanner.stepDetailColor
              }}
              item
              xs={8}
            >
              {formatMessage({ id: "workOfflineAndGo" })}
            </Grid>
          </Grid>
        ) : (
          <Grid columnSpacing={2} container rowGap={1}>
            <Grid item fontSize={18} textAlign="right" xs={4}>
              {formatMessage({ id: "one" })}
            </Grid>
            <Grid
              alignItems="center"
              alignSelf="center"
              sx={{
                color: theme => theme.palette.installationBanner.stepDetailColor
              }}
              item
              xs={8}
            >
              {formatMessage({ id: "installAresTouchFromStore" })}
            </Grid>
            <Grid fontSize={18} item textAlign="right" xs={4}>
              {formatMessage({ id: "two" })}
            </Grid>
            <Grid
              alignItems="center"
              alignSelf="center"
              sx={{
                color: theme => theme.palette.installationBanner.stepDetailColor
              }}
              item
              xs={8}
            >
              {formatMessage({ id: "openMenuAndTapAresTouch" })}
            </Grid>
            <Grid fontSize={18} item textAlign="right" xs={4}>
              {formatMessage({ id: "done" })}
            </Grid>
          </Grid>
        )}
        <Stack sx={{ width: "100%", rowGap: 0.5 }}>
          <KudoButton
            onClick={handleClickGetAresTouch}
            sx={{
              fontWeight: 700,
              borderRadius: 0,
              backgroundColor: theme =>
                theme.palette.installationBanner.buttonBg,
              height: 36,
              textTransform: "uppercase"
            }}
          >
            {formatMessage({ id: "getAresTouch" })}
          </KudoButton>
          <Button
            variant="text"
            sx={{
              color: theme => theme.palette.LIGHT,
              fontSize: "12px",
              height: 36
            }}
            onClick={handleClickSkip}
          >
            {formatMessage({
              id: isUniversalBanner ? "imNotInterested" : "skipThisTime"
            })}
          </Button>
        </Stack>
      </StyledSurface>
    </InstallationBannerWrapper>
  );
}
