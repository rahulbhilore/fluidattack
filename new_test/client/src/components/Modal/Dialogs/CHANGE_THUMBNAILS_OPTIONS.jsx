import React, { useEffect, useState } from "react";
import _ from "underscore";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import { Grid, Typography } from "@material-ui/core";
import Immutable from "immutable";
import MainFunctions from "../../../libraries/MainFunctions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ApplicationStore from "../../../stores/ApplicationStore";
import AdminActions from "../../../actions/AdminActions";
import SwitchBlock from "./ChangeUserOptions/SwitchBlock";
import StorageSwitch from "./ChangeUserOptions/StorageSwitch";
import StoragesListContainer from "./ChangeUserOptions/StoragesListContainer";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import Loader from "../../Loader";

const useStyles = makeStyles(() => ({
  body: {
    padding: 0
  }
}));

export default function changeThumbnailsOptions({ info }) {
  const [isLoading, setLoading] = useState(true);
  // const [storages, setStorages] = useState([]);
  const [connectedAccounts, setConnectedAccounts] = useState(
    new Immutable.Map()
  );

  const { userInfo } = info;

  const [isGenerallyAllowed, setGenerallyAllowed] = useState(
    MainFunctions.forceBooleanType(userInfo.options.disableThumbnail) === false
  );

  const [actions, setActions] = useState(new Immutable.List());

  const handleSubmit = () => {
    setLoading(true);
    // figure out what has changed
    const promises = actions.map(action =>
      AdminActions.disableThumbnails(
        userInfo._id,
        action.doDisable,
        action.externalId !== null
          ? { storages: { externalId: action.externalId } }
          : {}
      )
    );
    Promise.all(promises).finally(() => {
      AdminActions.getUserInfo(userInfo._id).finally(() => {
        setLoading(false);
        ModalActions.hide();
      });
    });
  };

  useEffect(() => {
    AdminActions.getListOfStoragesOnInstance().then(() => {
      AdminActions.getFullStoragesInfoForUser(userInfo._id).then(
        connectedAccountsInfo => {
          const newConnectedAccountsInfo = _.omit(
            connectedAccountsInfo,
            "status"
          );
          setConnectedAccounts(Immutable.fromJS(newConnectedAccountsInfo));
          setLoading(false);
        }
      );
    });
  }, [info]);

  const disableThumbnailsForAccount = (account, doDisable) => {
    const newActions = actions.filter(
      action => action.externalId !== account.get("sk")
    );
    setActions(newActions.push({ doDisable, externalId: account.get("sk") }));
  };

  const disableThumbnailsForUser = doDisable => {
    // remove all old actions
    const newActions = actions.filter(action => action.externalId !== null);
    setActions(newActions.unshift({ doDisable, externalId: null }));
    setGenerallyAllowed(!doDisable);
  };

  const { externalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");
  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="userThumbnailsForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
        >
          {isLoading ? (
            <Loader isModal />
          ) : (
            <>
              {/* For user as a whole */}
              <SwitchBlock
                id="disableThumbnail"
                defaultChecked={
                  // I think it's easier to understand if it's true by default
                  isGenerallyAllowed
                }
                onChange={v => {
                  disableThumbnailsForUser(!v);
                }}
                name="disableThumbnail"
                label="userHasThumbnails"
                formId="userThumbnailsForm"
              />
              {externalStoragesAvailable && connectedAccounts.size > 0 ? (
                <StoragesListContainer>
                  <Grid item xs={12}>
                    <Typography variant="body1">
                      <FormattedMessage id="perAccountConfiguration" />
                    </Typography>
                  </Grid>
                  {connectedAccounts.entrySeq().map(([storageName, l]) => (
                    <>
                      {l.map(accountDetails => (
                        <StorageSwitch
                          formId="userThumbnailsForm"
                          key={`userOptions_${storageName}_${accountDetails.get(
                            "sk"
                          )}`}
                          storageName={storageName}
                          onChange={v => {
                            disableThumbnailsForAccount(accountDetails, !v);
                          }}
                          disabled={!isGenerallyAllowed}
                          accountInfo={
                            accountDetails.get("email")
                              ? `${accountDetails.get(
                                  "email"
                                )} (${accountDetails.get("external_id")})`
                              : ""
                          }
                          defaultChecked={
                            accountDetails.has("disableThumbnail")
                              ? !accountDetails.get("disableThumbnail")
                              : true
                          }
                          fullWidth
                        />
                      ))}
                    </>
                  ))}
                </StoragesListContainer>
              ) : null}
            </>
          )}
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isDisabled={false} formId="userThumbnailsForm" isSubmit>
          <FormattedMessage id="save" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
