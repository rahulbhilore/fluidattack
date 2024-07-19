import { Collapse, Grid } from "@mui/material";
import { DateTime } from "luxon";
import React, { useContext, useEffect, useMemo, useState } from "react";
import { useIntl } from "react-intl";
import FilesListActions from "../../../../../../actions/FilesListActions";
import Loader from "../../../../../Loader";
import { PermissionsDialogContext } from "../../PermissionsDialogContext";
import SectionHeader from "../../SectionHeader";
import AllowViewersPrintToPdf from "./Sections/AllowViewersPrintToPdf";
import Expiration from "./Sections/Expiration";
import GeneratedPublicLink from "./Sections/GeneratedPublicLink";
import SetPasswordProtection from "./Sections/SetPasswordProtection";

export default function CreatePublicFileLink() {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isLoading, setLoading] = useState(false);
  const {
    publicAccess: {
      endTime: endTimeProp,
      fileId,
      isDeleted,
      isExport,
      isExportAvailable,
      isPasswordRequired: isPasswordRequiredProp,
      isPublic,
      isViewOnly,
      setGeneratedLink,
      updateObjectInfo
    }
  } = useContext(PermissionsDialogContext);
  const [exported, setExported] = useState(isExport);
  const parsedTime = parseInt(endTimeProp?.toString?.() ?? "0", 10);
  const [endTime, setEndTime] = useState<number>(parsedTime);
  const [passwordHeader, setPassword] = useState("");
  const [isPasswordRequired, setPasswordRequired] = useState(
    isPasswordRequiredProp
  );
  const { formatMessage } = useIntl();

  const specialDimensions = useMemo(
    () => ({
      height: 24,
      paddingX: 2,
      width: 42,
      thumbHeight: 20,
      thumbWidth: 20
    }),
    []
  );

  const onExpandButtonClick = () => {
    setIsExpanded(p => !p);
  };

  const createPublicLink = () => {
    if (isPublic && !isViewOnly && !isDeleted) {
      setLoading(true);
      FilesListActions.createPublicLink({
        fileId,
        isExport: exported,
        endTime,
        password:
          isPasswordRequired && passwordHeader.length > 0 ? passwordHeader : "",
        resetPassword: !isPasswordRequired
      }).then(newLink => {
        setLoading(false);
        if (
          !location.pathname.includes("app") &&
          !location.pathname.includes("search")
        )
          FilesListActions.modifyEntity(fileId, {
            public: true,
            link: newLink
          });
        setGeneratedLink(newLink);
        setPassword("");
        updateObjectInfo();
      });
    }
  };

  useEffect(() => {
    // no need to trigger if endTime is before now
    if (endTime === 0 || endTime > Date.now()) {
      createPublicLink();
    }
  }, [isPublic, exported, endTime]);

  const handleChangeExportSwitch = (_: unknown, checked: boolean) => {
    setExported(checked);
  };

  const setExpirationTime = (dateObject: Date) => {
    if (dateObject.getTime() === 0) {
      setEndTime(0);
    } else {
      setEndTime(
        DateTime.fromMillis(dateObject.getTime()).endOf("day").toMillis()
      );
    }
  };

  const togglePassword = () => {
    if (isPasswordRequired) {
      setPasswordRequired(false);
    } else {
      setPasswordRequired(true);
    }
  };

  useEffect(() => {
    if (!isPasswordRequired && isPasswordRequiredProp) {
      createPublicLink();
    }
  }, [isPasswordRequired]);
  useEffect(() => {
    if (isPasswordRequired && passwordHeader.length > 0) {
      createPublicLink();
    }
  }, [passwordHeader, isPasswordRequired]);

  if (isLoading && location.pathname.includes("/app/")) return <Loader />;
  return (
    <Grid container rowGap={1.5}>
      <Grid xs={12}>
        <SectionHeader
          header={formatMessage({ id: "createPublicFileLink" })}
          {...(!isViewOnly
            ? { expandButton: { isExpanded, onExpandButtonClick } }
            : {})}
        />
      </Grid>
      <Grid xs={12} data-header="generated-link">
        <GeneratedPublicLink />
      </Grid>

      {!isViewOnly && (
        <Grid xs={12}>
          <Collapse in={isExpanded} sx={{ width: "100%" }}>
            <Grid xs={12} container rowGap={1.5}>
              {isExportAvailable && (
                <Grid xs={12} data-header="allow-viewers-print-to-pdf">
                  <AllowViewersPrintToPdf
                    handleChangeExportSwitch={handleChangeExportSwitch}
                    switchDimensions={specialDimensions}
                  />
                </Grid>
              )}

              <Grid xs={12} data-header="expiration">
                <Expiration
                  disabled={!isPublic}
                  linkEndTime={new Date(endTime)}
                  setExpirationTime={setExpirationTime}
                />
              </Grid>

              <Grid xs={12} data-header="set-password-protection">
                <SetPasswordProtection
                  switchDimensions={specialDimensions}
                  togglePassword={togglePassword}
                  isPasswordRequired={isPasswordRequired}
                  setPassword={setPassword}
                />
              </Grid>
            </Grid>
          </Collapse>
        </Grid>
      )}
    </Grid>
  );
}
