import { OWNER_TYPES } from "../BaseResourcesStore";
import { FontsStore } from "./FontsStore";

/**
 * Company fonts store
 * @class CompanyFontsStore
 */
export class CompanyFontsStore extends FontsStore {
  /**
   * @inheritdoc
   */
  protected currentOwnerType = OWNER_TYPES.ORG;

  /**
   * @inheritdoc
   */
  protected storageOwnerTypes: OWNER_TYPES[] = [OWNER_TYPES.ORG];

  /**
   * @inheritdoc
   */
  protected indexDbResourceType = "C_FONTS";

  /**
   * @inheritdoc
   */
  protected storageClassName = "CompanyFontsStore";
}

const companyFontsStore = new CompanyFontsStore();
export default companyFontsStore;
