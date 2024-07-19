import React from "react";
import PropTypes from "prop-types";
import ModalActions from "../../actions/ModalActions";
import MenuItem from "./MenuItem";
import deleteSVG from "../../assets/images/context/delete.svg";
import renameSVG from "../../assets/images/context/rename.svg";
import {
  CUSTOM_TEMPLATES,
  PUBLIC_TEMPLATES
} from "../../constants/TemplatesConstants";
import FilesListActions from "../../actions/FilesListActions";
import { RenderFlags, RenderFlagsParams } from "./ContextMenu";

export type TemplateEntity = {
  id: string;
};

export function getRenderFlags({
  ids,
  infoProvider
}: RenderFlagsParams): RenderFlags {
  if (ids.length < 1) {
    return {
      isNeedToRenderMenu: false,
      entities: [],
      type: "templates"
    };
  }
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<TemplateEntity>;
  return {
    isNeedToRenderMenu: true,
    entities,
    type: "templates"
  };
}

export default function Templates({
  entities
}: {
  entities: Array<TemplateEntity>;
}) {
  // just to be sure
  if (entities.length < 1) return null;
  const firstEntity = entities[0];

  const rename = () => {
    FilesListActions.setEntityRenameMode(firstEntity.id);
  };
  const deleteTemplate = () => {
    const templateType = location.pathname.includes(
      "resources/templates/public"
    )
      ? PUBLIC_TEMPLATES
      : CUSTOM_TEMPLATES;
    ModalActions.deleteObjects("templates", entities, templateType);
  };

  const optionsList = [];
  if (entities.length === 1) {
    optionsList.push(
      <MenuItem
        id="contextMenuRenameTemplate"
        onClick={rename}
        image={renameSVG}
        caption="rename"
        key="contextMenuRenameTemplate"
        dataComponent="rename-template"
      />
    );
  }
  optionsList.push(
    <MenuItem
      id="contextMenuDeleteTemplate"
      onClick={deleteTemplate}
      image={deleteSVG}
      caption="delete"
      key="contextMenuDeleteTemplate"
      dataComponent="delete-template"
    />
  );
  return optionsList;
}

Templates.propTypes = {
  entities: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.string
    })
  ).isRequired
};
