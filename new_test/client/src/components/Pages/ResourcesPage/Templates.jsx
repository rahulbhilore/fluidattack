import React, { useEffect, useState } from "react";
import { useIntl } from "react-intl";
import { List, Map } from "immutable";
import makeStyles from "@material-ui/core/styles/makeStyles";
import PropTypes from "prop-types";
import SmartTable from "../../SmartTable/SmartTable";
import TemplatesActions from "../../../actions/TemplatesActions";
import TemplatesStore, {
  CUSTOM_TEMPLATES_LOADED,
  TEMPLATES_LOADED,
  TEMPLATE_DELETED,
  TEMPLATE_UPLOADED,
  TEMPLATE_UPDATED
} from "../../../stores/TemplatesStore";
import Toolbar from "../../Toolbar/Toolbar";
import userInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import {
  CUSTOM_TEMPLATES,
  PUBLIC_TEMPLATES
} from "../../../constants/TemplatesConstants";
import ModalActions from "../../../actions/ModalActions";
import FilesListActions from "../../../actions/FilesListActions";

import name from "../../SmartTable/tables/oldTemplates/Name";

const columns = new List([{ dataKey: "name", label: "name" }]);
const presentation = new Map({
  name
});
const useStyles = makeStyles(theme => ({
  table: {
    "& .ReactVirtualized__Table__headerRow": {
      paddingLeft: theme.spacing(3),
      "& .ReactVirtualized__Table__headerColumn": {
        fontSize: theme.typography.pxToRem(11)
      }
    },
    "& .ReactVirtualized__Table__sortableHeaderIcon": {
      width: "22px",
      height: "22px",
      verticalAlign: "middle"
    },
    "& .ReactVirtualized__Table__Grid .noDataRow": {
      color: theme.palette.JANGO
    }
  }
}));

const customSorts = {
  name: (a, b) => a.get("name").localeCompare(b.get("name"))
};

export default function TemplatesLoader(props) {
  const { type } = props;
  const classes = useStyles();
  const intl = useIntl();

  const [isFullInfo, setFullInfo] = useState(
    userInfoStore.getUserInfo("isFullInfo")
  );
  const [templates, setTemplates] = useState(new List());
  const [isLoading, setLoading] = useState(false);

  const loadTemplates = () => {
    setTemplates(new List());
    setLoading(true);
    TemplatesActions.loadTemplates(type);
  };

  const updateTemplates = () => {
    const currentType = document.location.pathname.includes("public")
      ? PUBLIC_TEMPLATES
      : CUSTOM_TEMPLATES;
    setTemplates(TemplatesStore.getTemplates(currentType));
  };

  const onTemplatesLoaded = () => {
    // we should recheck, because listeners don't take info from current component
    const currentType = document.location.pathname.includes("public")
      ? PUBLIC_TEMPLATES
      : CUSTOM_TEMPLATES;
    setLoading(false);
    setTemplates(TemplatesStore.getTemplates(currentType));
  };

  const userInfoUpdated = () => {
    const isLoaded = userInfoStore.getUserInfo("isFullInfo");
    if (isLoaded) {
      setFullInfo(true);
      userInfoStore.removeChangeListener(INFO_UPDATE, userInfoUpdated);
    }
  };

  useEffect(() => {
    TemplatesStore.addChangeListener(TEMPLATES_LOADED, onTemplatesLoaded);
    TemplatesStore.addChangeListener(
      CUSTOM_TEMPLATES_LOADED,
      onTemplatesLoaded
    );
    TemplatesStore.addChangeListener(TEMPLATE_UPDATED, updateTemplates);
    if (!isFullInfo)
      userInfoStore.addChangeListener(INFO_UPDATE, userInfoUpdated);
    loadTemplates();
    return () => {
      TemplatesStore.removeChangeListener(TEMPLATES_LOADED, onTemplatesLoaded);
      TemplatesStore.removeChangeListener(
        CUSTOM_TEMPLATES_LOADED,
        onTemplatesLoaded
      );
      TemplatesStore.removeChangeListener(TEMPLATE_DELETED, loadTemplates);
      TemplatesStore.removeChangeListener(TEMPLATE_UPLOADED, loadTemplates);
      TemplatesStore.removeChangeListener(TEMPLATE_UPDATED, updateTemplates);
      userInfoStore.removeChangeListener(INFO_UPDATE, userInfoUpdated);
    };
  }, []);

  useEffect(loadTemplates, [type]);

  const handleDelete = ids => {
    const entities = ids
      .map(id => templates.find(t => t.get("id") === id))
      .filter(v => v !== undefined)
      .map(iMap => iMap.toJS());
    ModalActions.deleteObjects("templates", entities, type);
  };

  const handleRename = id => {
    FilesListActions.setEntityRenameMode(id);
  };

  return (
    <>
      <Toolbar isMobile={false} />
      <SmartTable
        customSorts={customSorts}
        noDataCaption={intl.formatMessage({ id: "noFilesInCurrentFolder" })}
        data={templates}
        presentation={presentation}
        columns={columns}
        classes={classes}
        handleDelete={handleDelete}
        handleRename={handleRename}
        tableType="oldTemplates"
        isLoading={isLoading}
      />
    </>
  );
}

TemplatesLoader.propTypes = {
  type: PropTypes.oneOf([CUSTOM_TEMPLATES, PUBLIC_TEMPLATES]).isRequired
};
