/**
 * Created by khizh on 8/25/2016.
 */
import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import List from "@material-ui/core/List";
import ListItem from "@material-ui/core/ListItem";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import MainFunctions from "../../../libraries/MainFunctions";
import { BLOCK, LIBRARY } from "../../../stores/BlocksStore";
import BlocksActions from "../../../actions/BlocksActions";

const dummyCheckFunction = () => true;
const useStyles = makeStyles(theme => ({
  body: {
    paddingLeft: 0,
    paddingRight: 0
  },
  caption: {
    fontSize: theme.typography.pxToRem(14),
    textAlign: "center",
    marginBottom: theme.spacing(1)
  },
  text: {
    color: theme.palette.DARK,
    userSelect: "text"
  },
  warningText: {
    fontSize: theme.typography.pxToRem(12)
  },
  list: {
    paddingBottom: 0,
    paddingTop: theme.spacing(1),
    borderTop: `solid 1px ${theme.palette.DARK}`
  },
  listItem: {
    justifyContent: "center"
  }
}));

export default function removalConfirmationDialog({ info }) {
  const handleSubmit = () => {
    const { entities, type } = info;
    const ids = entities.map(ent => ent.id);
    if (type === BLOCK) {
      BlocksActions.deleteMultipleBlocks(ids, entities[0].libId);
    } else if (type === LIBRARY) {
      BlocksActions.deleteMultipleBlockLibraries(ids);
    }
    ModalActions.hide();
  };

  const classes = useStyles();
  return (
    <>
      <DialogBody className={classes.body}>
        <KudoForm
          id="deleteConfirmationForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
          checkFunction={dummyCheckFunction}
        >
          {info.entities ? (
            <>
              <Typography
                variant="body1"
                className={clsx(classes.text, classes.caption)}
              >
                <FormattedMessage id="pleaseConfirmDeletion" />
              </Typography>
              <List className={classes.list}>
                {(info.entities || []).map(entity => (
                  <ListItem key={entity.id} className={classes.listItem}>
                    <Typography
                      className={clsx(classes.text, classes.warningText)}
                    >
                      <FormattedMessage
                        id="drawingIsBeingEditedByNow"
                        values={{
                          name: ` ${MainFunctions.shrinkString(
                            entity.name || "Unknown",
                            40
                          )} `,
                          editors: ` ${(entity.editors || [])
                            .map(e => e.email || "")
                            .filter(v => v.length > 0)
                            .join(", ")} `,
                          strong: IntlTagValues.strong
                        }}
                      />
                    </Typography>
                  </ListItem>
                ))}
              </List>
            </>
          ) : null}
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton formId="deleteConfirmationForm" isSubmit isDisabled={false}>
          <FormattedMessage id="delete" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
