import React, { useEffect, useState } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import _ from "underscore";
import { Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import FilesListActions from "../../../actions/FilesListActions";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import SingleDuplicate from "../../Inputs/SingleDuplicate/SingleDuplicate";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import Requests from "../../../utils/Requests";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import Loader from "../../Loader";
import ModalActions from "../../../actions/ModalActions";
import ProcessActions from "../../../actions/ProcessActions";
import FormManagerActions from "../../../actions/FormManagerActions";
import FormManagerStore from "../../../stores/FormManagerStore";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import MainFunctions from "../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  caption: {
    marginBottom: theme.spacing(2),
    textAlign: "center"
  }
}));

const RECHECK_EVENT = "recheckDuplicates";

export default function restoreDuplicates({ info }) {
  const restoreFilesByParents = _.groupBy(info.duplicates, "parent");
  const parents = _.uniq(Object.keys(restoreFilesByParents));

  const [isLoading, setLoading] = useState(true);
  const [entities, setEntities] = useState(_.object(parents, []));
  const [areDuplicates, setDuplicatesFlag] = useState(false);
  const classes = useStyles();
  const { formatMessage } = useIntl();

  const checkParentFolder = parentId =>
    new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/folders/${parentId}`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders()
      ).then(answer => {
        const { data } = answer;
        if (!data.results) {
          reject(new Error(`No results for parentId: ${parentId}`));
        } else {
          // save in format name:id
          // for each entity to restore we should check if newName either isn't in keys
          // or value is an array length of 1 && value === currentFileId
          // entities[parentId] = {
          //   file: {
          //     A: [0],
          //     B: [1, 5],
          //     C: [2],
          //     X: [3],
          //     Y: [4]
          //   },
          //   folder: {}
          // };
          entities[parentId] = {
            file: {},
            folder: {}
          };
          data.results.files.forEach(({ _id, filename }) => {
            if (!entities[parentId].file[filename]) {
              entities[parentId].file[filename] = [];
            }
            entities[parentId].file[filename].push(_id);
          });
          data.results.folders.forEach(({ _id, name }) => {
            if (!entities[parentId].folder[name]) {
              entities[parentId].folder[name] = [];
            }
            entities[parentId].folder[name].push(_id);
          });
          restoreFilesByParents[parentId].forEach(
            ({ name, filename, type, _id }) => {
              const endName = name || filename;
              if (!entities[parentId][type][endName]) {
                entities[parentId][type][endName] = [];
              }
              entities[parentId][type][endName].push(_id);
            }
          );
          setEntities(entities);
          resolve();
        }
      });
    });

  useEffect(() => {
    const promises = parents.map(checkParentFolder);
    Promise.all(promises)
      .then(() => {
        setDuplicatesFlag(
          Object.values(entities).some(
            obj =>
              // this is the "cache"
              // obj = {file: {A:[1], B:[2]},folder:{}}
              Object.values(obj.file).some(idArr => idArr.length > 1) ||
              Object.values(obj.folder).some(idArr => idArr.length > 1)
          )
        );
        setLoading(false);
      })
      .catch(err => {
        SnackbarUtils.alertError(err);
      });
  }, []);

  const handleSubmit = json => {
    const originalList = _.mapObject(json, val => {
      const extension = MainFunctions.getExtensionFromName(val.value.oldName);
      return {
        ...val.value,
        name:
          val.value.type === "file" && extension.length > 0
            ? `${val.value.name}.${extension}`
            : val.value.name
      };
    });
    const { false: restore, true: notRestore = [] } = _.groupBy(
      originalList,
      "cancelFlag"
    );
    restore.forEach(item => ProcessActions.start(item.id));
    FilesListActions.restoreEntities(originalList)
      .then(() => {
        // empty first element to have a space between message and list
        const entitiesToBeRestored = [""].concat(
          restore.map((restoredEntity, index) => {
            let restoreName = restoredEntity.oldName;
            if (restoredEntity.name !== restoredEntity.oldName) {
              restoreName = formatMessage(
                { id: "objectAsNewName" },
                {
                  oldname: restoredEntity.oldName,
                  newname: restoredEntity.name
                }
              );
            }
            FilesListActions.deleteEntity(
              restoredEntity.id,
              index === restore.length - 1
            );
            ProcessActions.end(restoredEntity.id);
            return restoreName;
          })
        );

        let message = [
          {
            id: "filesHaveBeenRestored",
            names: entitiesToBeRestored.join("\r\n")
          }
        ];
        if (notRestore.length) {
          message = [
            ...message,
            {
              id: "notRestoredBecauseCancelled",
              names: notRestore.map(({ oldName }) => oldName).join(",")
            }
          ];
          SnackbarUtils.alertWarning(message);
        } else {
          SnackbarUtils.alertOk(message);
        }
      })
      .catch(err => {
        SnackbarUtils.alertError(JSON.parse(err.message));
      });
    ModalActions.hide();
  };

  const removeFromCache = (parent, type, id, name, withRecheck = true) => {
    if (entities[parent][type][name]) {
      entities[parent][type][name] = entities[parent][type][name].filter(
        v => v !== id
      );
    }
    if (withRecheck) {
      const anyToRestore = Object.values(
        FormManagerStore.getAllFormElementsData("restoreDuplicatesForm")
      ).some(o => o.value?.doRestore === true);

      if (anyToRestore) document.dispatchEvent(new CustomEvent(RECHECK_EVENT));
      else {
        const submitButtonId = FormManagerStore.getButtonIdForForm(
          "restoreDuplicatesForm"
        );
        FormManagerActions.changeButtonState(
          "restoreDuplicatesForm",
          submitButtonId,
          false
        );
      }
    }
  };

  const updateEntitiesCache = (parent, type, id, newName, oldName) => {
    removeFromCache(parent, type, id, oldName, false);

    if (!entities[parent][type][newName]) {
      entities[parent][type][newName] = [];
    }
    entities[parent][type][newName].push(id);
    document.dispatchEvent(new CustomEvent(RECHECK_EVENT));
  };
  const checkDuplicates = (parent, type, id, name) =>
    (entities[parent][type][name] || []).length === 1;
  return (
    <>
      <DialogBody>
        {!isLoading ? (
          <div className={classes.caption}>
            {areDuplicates ? (
              <Typography variant="body1">
                <FormattedMessage id="usedNameRestore" />
                <br />
              </Typography>
            ) : null}
            <FormattedMessage
              id="youCanRenameOrChooseRestore"
              values={{ strong: IntlTagValues.strong }}
            />
          </div>
        ) : (
          <Loader isModal message={<FormattedMessage id="analyzing" />} />
        )}
        {!isLoading ? (
          <KudoForm
            id="restoreDuplicatesForm"
            onSubmitFunction={handleSubmit}
            checkOnMount
          >
            {(info.duplicates || []).map(duplicate => (
              <SingleDuplicate
                parentInfo={entities[duplicate.parent][duplicate.type]}
                id={duplicate.id}
                defaultName={duplicate.name || duplicate.filename}
                type={duplicate.type}
                parent={duplicate.parent}
                mimeType={duplicate.mimeType}
                updateEntitiesCache={updateEntitiesCache}
                removeFromCache={removeFromCache}
                checkCurrentDuplicates={checkDuplicates}
                key={duplicate.id}
                formId="restoreDuplicatesForm"
              />
            ))}
          </KudoForm>
        ) : null}
      </DialogBody>
      {!isLoading ? (
        <DialogFooter>
          <KudoButton isSubmit formId="restoreDuplicatesForm">
            <FormattedMessage id="restore" />
          </KudoButton>
        </DialogFooter>
      ) : null}
    </>
  );
}
