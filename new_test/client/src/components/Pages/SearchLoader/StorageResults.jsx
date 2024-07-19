import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import ApplicationStore from "../../../stores/ApplicationStore";
import AccountLabel from "./AccountLabel";
import ControlledAccordion from "../../Inputs/Accordion/ControlledAccordion";
import AccountResults from "./AccountResults/AccountResults";

const useStyles = makeStyles(theme => ({
  root: {
    marginBottom: theme.spacing(3),
    padding: `0 ${theme.spacing(3)}px`,
    [theme.breakpoints.down("sm")]: {
      padding: `0 ${theme.spacing(1)}px`,
      marginBottom: theme.spacing(2)
    }
  },
  heading: {
    margin: theme.spacing(1.5, 0),
    textAlign: "left",
    fontSize: theme.typography.pxToRem(12),
    fontWeight: "bold",
    userSelect: "text",
    color: theme.palette.OBI
  }
}));

export default function StorageResults({ storageName, foundAccounts, query }) {
  const { externalStoragesAvailable: areExternalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");
  const classes = useStyles();
  return (
    <>
      {Object.keys(foundAccounts).map(externalId => {
        const accountData = foundAccounts[externalId];
        const { name: accountName } = accountData;
        const amountOfEndResults = Object.entries(accountData).reduce(
          (m, [k, v]) => {
            if (k === "name") return m;
            return m + v.length;
          },
          0
        );
        if (amountOfEndResults === 0) {
          return null;
        }
        return (
          <Grid
            item
            xs={12}
            key={`searchResultsBlock_${storageName}_${externalId}`}
            className={classes.root}
          >
            <ControlledAccordion
              dataComponent="storage_search_result"
              dataName={`${storageName}_${accountName}`}
              preserveChildrenRender
              classes={{ heading: classes.heading }}
              title={
                areExternalStoragesAvailable === false ? null : (
                  <AccountLabel storage={storageName} name={accountName} />
                )
              }
            >
              {Object.keys(accountData).map(folderId => {
                if (folderId !== "name") {
                  // "name" is used to store account name
                  return (
                    <AccountResults
                      key={`result_${storageName}_${externalId}_${folderId}`}
                      storageName={storageName}
                      folderId={folderId}
                      query={query}
                      externalId={externalId}
                      results={accountData[folderId]}
                    />
                  );
                }
                return null;
              })}
            </ControlledAccordion>
          </Grid>
        );
      })}
    </>
  );
}
StorageResults.propTypes = {
  storageName: PropTypes.string.isRequired,
  query: PropTypes.string.isRequired,
  foundAccounts: PropTypes.objectOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired
    }).isRequired
  ).isRequired
};
