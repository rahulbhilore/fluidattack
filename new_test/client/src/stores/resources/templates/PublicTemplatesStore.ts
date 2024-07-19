import { TemplatesStore } from "../templates/TempatesStore";
import { OWNER_TYPES } from "../BaseResourcesStore";

/**
 * Public templates store
 * @class PublicTemplatesStore
 */
export class PublicTemplatesStore extends TemplatesStore {
  /**
   * @inheritdoc
   */
  protected storageOwnerTypes: OWNER_TYPES[] = [OWNER_TYPES.PUBLIC];

  /**
   * @inheritdoc
   */
  protected storageClassName = "PublicTemplatesStore";

  /**
   * Type of owner of this font storage
   * @type {null|OWNER_TYPES}
   * @protected
   */
  protected currentOwnerType = OWNER_TYPES.PUBLIC;

  /**
   * @inheritdoc
   */
  protected indexDbResourceType = "P_TEMPLATES";
}

const publicTemplatesStore = new PublicTemplatesStore();
export default publicTemplatesStore;
