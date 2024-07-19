import React, { useEffect, useState } from "react";
import _ from "underscore";
import Immutable from "immutable";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import MainFunctions from "../../../libraries/MainFunctions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ApplicationStore from "../../../stores/ApplicationStore";
import AdminActions from "../../../actions/AdminActions";
import UsageAndQuota from "./ChangeUserOptions/UsageAndQuota";
import CreateDriveSkeleton from "./ChangeUserOptions/CreateDriveSkeleton";
import SwitchBlock from "./ChangeUserOptions/SwitchBlock";
import StorageSwitch from "./ChangeUserOptions/StorageSwitch";
import StoragesListContainer from "./ChangeUserOptions/StoragesListContainer";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";

const useStyles = makeStyles(() => ({
  body: {
    padding: 0
  }
}));

export default function changeUserOptions({ info }) {
  const [storages, setStorages] = useState([]);

  const { userInfo } = info;
  const handleSubmit = json => {
    AdminActions.changeUserOptions(
      userInfo._id,
      Immutable.fromJS({
        editor: MainFunctions.forceBooleanType(json.editor.value),
        no_debug_log: !MainFunctions.forceBooleanType(json.debug_log.value),
        allowURLChange: MainFunctions.forceBooleanType(
          json.allowURLChange.value
        ),
        quota: parseInt(json.quota.value, 10),
        storages: _.mapObject(info.userInfo.options.storages, (value, key) =>
          json[key] !== undefined
            ? MainFunctions.forceBooleanType(json[key].value)
            : MainFunctions.forceBooleanType(value)
        )
      })
    ).then(() => {
      AdminActions.getUserInfo(userInfo._id);
    });
    ModalActions.hide();
  };

  useEffect(() => {
    AdminActions.getListOfStoragesOnInstance().then(data => {
      setStorages(data.instanceStorages.map(s => s.toLowerCase()));
    });
  }, [info]);

  const { externalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");
  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="userOptionsForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
        >
          <SwitchBlock
            id="editor"
            defaultChecked={
              MainFunctions.forceBooleanType(userInfo.options.editor) === true
            }
            name="editor"
            label="userHasAccessToEditor"
            dataComponent="userHasAccessToEditor"
          />
          <SwitchBlock
            id="debug_log"
            defaultChecked={
              MainFunctions.forceBooleanType(userInfo.options.no_debug_log) ===
              false
            }
            name="debug_log"
            label="userLogDebug"
          />
          <SwitchBlock
            id="allowURLChange"
            defaultChecked={
              MainFunctions.forceBooleanType(
                userInfo.options.allowURLChange
              ) === true
            }
            name="allowURLChange"
            label="userAllowedToChangeURL"
            dataComponent="userAllowedToChangeURL"
          />
          <UsageAndQuota
            usage={userInfo.usage}
            quota={userInfo.options.quota}
          />
          <CreateDriveSkeleton
            userId={userInfo._id}
            areSamplesCreated={!!userInfo.samplesCreated}
          />
          <StoragesListContainer>
            {externalStoragesAvailable
              ? storages.map(storageName => (
                  <StorageSwitch
                    key={`userOptions_${storageName}`}
                    storageName={storageName}
                    defaultChecked={
                      MainFunctions.forceBooleanType(
                        userInfo.options.storages[storageName]
                      ) === true
                    }
                  />
                ))
              : null}
          </StoragesListContainer>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isDisabled={false} formId="userOptionsForm" isSubmit>
          <FormattedMessage id="save" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
