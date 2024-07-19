import React, { PureComponent } from "react";
import PropTypes from "prop-types";
import { FormattedMessage, injectIntl } from "react-intl";
import Container from "@material-ui/core/Container";
import $ from "jquery";
import _ from "underscore";
import { Portal } from "@mui/material";
import Footer from "../../Footer/Footer";
import ApplicationStore from "../../../stores/ApplicationStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import UserInfoStore, {
  COMPANY_INFO_UPDATE,
  INFO_UPDATE
} from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import UserInfoActions from "../../../actions/UserInfoActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ApplicationActions from "../../../actions/ApplicationActions";
import Loader from "../../Loader";
import StorageSettings from "./StorageSettings";
import ViewOnlyLinksSettings from "./ViewOnlyLinksSettings";
import ToolbarSpacer from "../../ToolbarSpacer";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

let formatMessage = null;

class CompanyPage extends PureComponent {
  static calculatePageHeight() {
    const body = $(window);
    const footerHeight = 45;
    const companyPage = $("main#companyPage");
    let offset = companyPage.offset();
    let height = body.height();
    if (typeof companyPage !== "undefined" && typeof offset !== "undefined") {
      companyPage.css("height", `${height - offset.top - footerHeight}px`);
    }
    $(window).resize(() => {
      offset = companyPage.offset();
      height = body.height();
      companyPage.css("height", `${height - offset.top - footerHeight}px`);
    });
  }

  static updateCompanyInfo(formData) {
    const updateData = {};
    Object.keys(formData).forEach(key => {
      MainFunctions.setDeep(
        updateData,
        key.split("."),
        formData[key].value,
        true
      );
    });
    updateData.plMaxNumberOfDays =
      parseInt(updateData.plMaxNumberOfDays || "30", 10) || 30;
    UserInfoActions.updateCompanyInfo(UserInfoStore.getUserInfo("company").id, {
      options: updateData
    })
      .then(() => {
        SnackbarUtils.alertOk({ id: "companyInfoUpdated" });
        UserInfoActions.getCompanyInfo(UserInfoStore.getUserInfo("company").id);
        // update user's info to apply limitations
        UserInfoActions.getUserInfo();
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
      });
  }

  static propTypes = {
    intl: PropTypes.shape({ formatMessage: PropTypes.func.isRequired })
      .isRequired
  };

  constructor(props) {
    super(props);
    ({ formatMessage } = props.intl);
    this.state = {
      isLoading: true,
      arePublicLinksAllowed: true
    };
  }

  componentDidMount() {
    MainFunctions.updateBodyClasses([], ["init"]);
    if (
      UserInfoStore.getUserInfo("isLoggedIn") === true &&
      UserInfoStore.getUserInfo("isFullInfo") === true
    ) {
      this.onUserUpdate();
    } else {
      UserInfoStore.addChangeListener(INFO_UPDATE, this.onUserUpdate);
    }
    document.title = `${ApplicationStore.getApplicationSetting(
      "defaultTitle"
    )} | ${formatMessage({
      id: "company"
    })}`;
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUserUpdate);
    UserInfoStore.removeChangeListener(COMPANY_INFO_UPDATE, this.onUserUpdate);
  }

  onUserUpdate = () => {
    const companyId = UserInfoStore.getUserInfo("company").id;
    const { companiesAdmin, companiesAll } =
      ApplicationStore.getApplicationSetting("featuresEnabled");

    if (
      companiesAll === false &&
      (companiesAdmin === false ||
        UserInfoStore.getUserInfo("isAdmin") === false)
    ) {
      ApplicationActions.changePage(
        `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
      );
    }
    if (companyId && companyId.length > 0) {
      UserInfoActions.getCompanyInfo(UserInfoStore.getUserInfo("company").id);
      UserInfoStore.addChangeListener(
        COMPANY_INFO_UPDATE,
        this.onCompanyInfoUpdate
      );
    } else {
      ApplicationActions.changePage(
        `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
      );
    }
  };

  onCompanyInfoUpdate = () => {
    this.setState(
      {
        isLoading: false,
        arePublicLinksAllowed:
          UserInfoStore.getCompanyInfo().options.sharedLinks
      },
      CompanyPage.calculatePageHeight
    );
  };

  savePublicLinksFlag = arePublicLinksAllowed => {
    this.setState({ arePublicLinksAllowed });
  };

  render() {
    const defaultTitle = ApplicationStore.getApplicationSetting("defaultTitle");
    const existingStorages =
      ApplicationStore.getApplicationSetting("storagesSettings");
    const companyInfo = UserInfoStore.getCompanyInfo();
    const { isLoading, arePublicLinksAllowed } = this.state;
    if (!isLoading) {
      if (!companyInfo.options.storages) {
        companyInfo.options.storages = {};
      }
      const normalizedValues = existingStorages
        .filter(s => s.name.toLowerCase() !== "internal")
        .map(storage => [storage.name.toLowerCase(), true]);
      companyInfo.options.storages = _.defaults(
        companyInfo.options.storages,
        _.object(normalizedValues)
      );
    }
    return (
      <>
        {isLoading === true ? (
          <Portal isOpened>
            <Loader />
          </Portal>
        ) : (
          <main
            style={{ flexGrow: 1, overflowY: "auto", overflowX: "hidden" }}
            id="companyPage"
          >
            <ToolbarSpacer />
            <Container maxWidth="sm">
              <KudoForm
                id="companyInfo"
                checkFunction={formValues =>
                  _.every(formValues, val => val.valid)
                }
                checkOnMount
                onSubmitFunction={CompanyPage.updateCompanyInfo}
              >
                <StorageSettings
                  storagesList={existingStorages}
                  companyOptions={companyInfo.options.storages}
                />
                <ViewOnlyLinksSettings
                  arePublicLinksAllowed={arePublicLinksAllowed}
                  savePublicLinksFlag={this.savePublicLinksFlag}
                  companyOptions={companyInfo.options}
                />
                {/* Submit button */}
                <KudoButton
                  isSubmit
                  id="companyInfoSaveButton"
                  bsStyle="primary"
                  formId="companyInfo"
                  styles={{
                    button: {
                      backgroundColor: "#124DAF!important",
                      width: "100%"
                    },
                    typography: {
                      color: "#FFFFFF !important",
                      fontWeight: 100
                    }
                  }}
                >
                  <FormattedMessage id="save" />
                </KudoButton>
              </KudoForm>
            </Container>
          </main>
        )}
        {isLoading === true ? null : <Footer isFixed />}
      </>
    );
  }
}

export default injectIntl(CompanyPage);
