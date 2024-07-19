/* eslint-disable react/no-string-refs */
/**
 * Created by khizh on 9/4/2015.
 */
import React, { Component } from "react";
import propTypes from "prop-types";
import $ from "jquery";
import _ from "underscore";
import { Shortcuts } from "react-shortcuts";
import { FormattedMessage, injectIntl } from "react-intl";
import { Grid, Box, styled } from "@material-ui/core";
import UserInfoStore from "../../stores/UserInfoStore";
import ContextMenuStore from "../../stores/ContextMenuStore";
import "./TableView.scss";
import MainFunctions from "../../libraries/MainFunctions";
import ModalActions from "../../actions/ModalActions";
import ModalStore from "../../stores/ModalStore";
import ApplicationStore from "../../stores/ApplicationStore";
import FilesListStore, { CONTENT_LOADED } from "../../stores/FilesListStore";
import TableStore, {
  CONTENT_CHANGE,
  FIELD_EVENT,
  CHANGE_EVENT,
  SELECT_EVENT,
  RECALCULATE_EVENT
} from "../../stores/TableStore";
import TableActions from "../../actions/TableActions";
import TableRow from "./TableRow";
import StorageContext from "../../context/StorageContext";
import sortASCSVG from "../../assets/images/sort-asc.svg";
import sortDESCSVG from "../../assets/images/sort-desc.svg";
import {
  CUSTOM_TEMPLATES,
  PUBLIC_TEMPLATES
} from "../../constants/TemplatesConstants";
import Loader from "../Loader";
import InlineLoader from "./InlineLoader";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import NoFileFoundSVG from "../../assets/images/NoFileFound.svg";

let formatMessage = null;

const StyledSortIcon = styled("img")(({ theme }) => ({
  marginBottom: theme.typography.pxToRem(-5)
}));

/**
 * Scroll tbody to currently selected row if it's not visible
 * @param id {String} - row id
 */
function smartScrollTo(id) {
  // 50 - row height
  let realRowOffset =
    $(document.getElementById(id)).offset().top - $("tbody").offset().top;
  if (realRowOffset >= $("tbody").height() || realRowOffset < 0) {
    if (realRowOffset >= $("tbody").height()) {
      realRowOffset -= $("tbody").height() - 50;
    }
    $("tbody")
      .stop(true, true)
      .scrollTop($("tbody").scrollTop() + realRowOffset);
  }
}

class TableView extends Component {
  static propTypes = {
    gridClassName: propTypes.string,
    isInDialog: propTypes.bool,
    isPadded: propTypes.bool,
    id: propTypes.string.isRequired,
    intl: propTypes.shape({
      formatMessage: propTypes.func.isRequired
    }).isRequired,
    getObjects: propTypes.func.isRequired,
    doesNextPageExist: propTypes.func.isRequired
  };

  static defaultProps = {
    gridClassName: "",
    isInDialog: false,
    isPadded: false
  };

  static contextType = StorageContext;

  constructor(props) {
    super(props);
    ({ formatMessage } = props.intl);
    const { id } = props;
    this.state = {
      _id: id,
      selection: [],
      content: TableStore.getTable(id) || {}
    };
  }

  componentDidMount() {
    const { _id } = this.state;
    TableStore.addListener(FIELD_EVENT, this.onFieldEvent);
    TableStore.addListener(CHANGE_EVENT, this.onContentChange);
    TableStore.addListener(SELECT_EVENT, this.onSelectionChange);
    TableStore.addListener(RECALCULATE_EVENT, this.setTableDimensions);
    TableStore.addListener(CONTENT_CHANGE, this.onContentChange);
    if (!TableStore.isTableRegistered(_id)) {
      TableActions.registerTable(_id);
    }
    // possible fix for XENON-27025
    setTimeout(() => {
      this.setTableDimensions();
      this.setJqueryListeners();
    }, 500);
    FilesListStore.addEventListener(CONTENT_LOADED, this.onContentFilesLoaded);
  }

  componentWillUnmount() {
    TableStore.removeListener(CHANGE_EVENT, this.onContentChange);
    TableStore.removeListener(FIELD_EVENT, this.onFieldEvent);
    TableStore.removeListener(SELECT_EVENT, this.onSelectionChange);
    TableStore.removeListener(RECALCULATE_EVENT, this.setTableDimensions);
    TableStore.removeListener(CONTENT_CHANGE, this.onContentChange);
    MainFunctions.detachJQueryListeners($(document), "click");
    MainFunctions.detachJQueryListeners(
      $("table.tableViewMain tbody"),
      "scroll"
    );
    const { _id } = this.state;
    TableActions.unRegisterTable(_id);
    MainFunctions.detachJQueryListeners($(window), "resize");
    FilesListStore.removeListener(CONTENT_LOADED, this.onContentFilesLoaded);
  }

  setTableDimensions = () => {
    const { isInDialog } = this.props;
    const { _id } = this.state;
    const pageType = MainFunctions.detectPageType();
    // TODO: replace with checking of registered tables number

    if (!pageType.includes("search")) {
      let body = $(window);

      let footerHeight = 45;
      if (pageType.includes("files")) {
        if (
          !ApplicationStore.getApplicationSetting("customization")
            .showFooterOnFileLoaderPage
        ) {
          footerHeight = 0;
        }
      }

      let height = body.height();
      if (isInDialog) {
        const dialogQuery = document.querySelectorAll(
          "div[data-component='modal-block'] div[role='dialog']"
        );
        if (dialogQuery.length > 0) {
          body = $(dialogQuery[0]);
          height = body.height();
        }
      }
      let topOffset = 0;

      if (MainFunctions.detectPageType() === "users") {
        topOffset = 10;
      }
      const table = $(`table#${_id}`);
      const tbody = table.find("tbody");

      const offset = table.offset();

      if (typeof table !== "undefined" && typeof offset !== "undefined") {
        const oldTableHeight = table.height();
        const newHeight = isInDialog
          ? height - 180
          : height - offset.top - footerHeight - topOffset;
        if (oldTableHeight !== newHeight) {
          table.css("height", `${newHeight}px`);
          tbody.css("height", `${newHeight - 30}px`);
        }

        const mainWidth = $("div.main").width() || body.width();

        tbody
          .find(".tableColumn:not(.noFiles):first-child")
          .css("max-width", `${mainWidth / 2}px`);

        MainFunctions.attachJQueryListener($(window), "resize", () => {
          const resizeTable = $("table.tableViewMain");
          const resizeTbody = resizeTable.find("tbody");
          if (resizeTable && resizeTbody) {
            const currentHeight = resizeTable.height();
            const offsetOnResize = resizeTable.offset();
            const heightOnResize = body.height();
            if (offsetOnResize && heightOnResize) {
              const propozedHeight = isInDialog
                ? heightOnResize - 180
                : heightOnResize -
                  offsetOnResize.top -
                  footerHeight -
                  topOffset;
              if (currentHeight !== propozedHeight) {
                resizeTable.css("height", `${propozedHeight}px`);
                resizeTbody.css("height", `${propozedHeight - 30}px`);
              }
              const mainWidthOnResize = $("div.main").width() || body.width();

              resizeTbody
                .find(".tableColumn:not(.noFiles):first-child")
                .css("max-width", `${mainWidthOnResize / 2}px`);
            }
          }
        });
      }
    }
  };

  onFieldEvent = () => {
    const { _id } = this.state;
    TableStore.getSelection(_id).forEach(selectedItem => {
      const rowInfo = TableStore.getRowInfo(_id, selectedItem.id);
      if (rowInfo) {
        if (rowInfo.lastEvent) {
          this.refs[`tableObject_${selectedItem._id}`].handleEvents(
            rowInfo.lastEvent
          );
        } else {
          this.refs[`tableObject_${selectedItem._id}`].forceUpdate();
        }
      } else {
        // eslint-disable-next-line no-console
        console.error("Can't get row info for row with id", selectedItem.id);
      }
    });
  };

  onContentChange = () => {
    const { _id } = this.state;
    this.setState({ content: TableStore.getTable(_id) });
  };

  onContentFilesLoaded = () => {
    this.setState({
      isLoading: false
    });
  };

  onSelectionChange = () => {
    const { _id } = this.state;
    this.setState({ selection: TableStore.getSelection(_id) || [] });
  };

  onKeyEvent = (action, event) => {
    const { _id } = this.state;
    if (
      $("input:focus").length === 0 &&
      ((ModalStore.isDialogOpen() === false &&
        ContextMenuStore.getCurrentInfo().isVisible === false) ||
        action === "DESELECT") &&
      // I.A. this is awful solution but as we re going to refactor on
      // SmartTable soon, this solution is admissible
      // for SmartTable we should use something like SmartTableStore.lockKeyHandling()
      $(".tableFilesFilterOpened").length === 0
    ) {
      const tableRows = TableStore.getTable(_id).results;
      const selectedRows = TableStore.getSelection(_id);
      switch (action) {
        case "SELECT_UP":
        case "SELECT_DOWN":
          // Arrow Down || Up
          if (!TableStore.performingAnyAction(_id)) {
            let selectedRowId = null;
            let rowsToSelect = [];
            let selectedRowObject = null;
            if (selectedRows.length) {
              if (action === "SELECT_DOWN") {
                rowsToSelect = $("tr.selected").last().next();
              } else {
                rowsToSelect = $("tr.selected").first().prev();
              }
              if (rowsToSelect.length) {
                selectedRowId = rowsToSelect.attr("id");
                selectedRowObject = _.findWhere(tableRows, {
                  id: selectedRowId
                });
                if (Object.keys(selectedRowObject || {}).length) {
                  // TODO: shiftKey
                  /* if (e.shiftKey) {
                   TableActions.addToSelected(selectedRowObject, true);
                   } else {
                   */
                  TableActions.selectObject(_id, selectedRowObject);
                  // }
                  smartScrollTo(selectedRowId);
                }
              }
            } else {
              [rowsToSelect] = $("tbody > tr");
              if (rowsToSelect) {
                selectedRowId = rowsToSelect.getAttribute("id");
                selectedRowObject = _.findWhere(tableRows, {
                  id: selectedRowId
                });
                if (Object.keys(selectedRowObject || {}).length) {
                  TableActions.selectObject(_id, selectedRowObject);
                  smartScrollTo(selectedRowId);
                }
              }
            }
          }
          break;
        case "DESELECT":
          // ESC
          if (
            !TableStore.performingAnyAction(_id) &&
            TableStore.getSelection(_id).length
          ) {
            TableActions.removeSelection();
          }
          break;
        // TODO
        case "SELECT_ALL":
          // ctrl+A
          if (
            !TableStore.performingAnyAction(_id) &&
            TableStore.getTable(_id).multiSelect
          ) {
            event.preventDefault();
            event.stopPropagation();
            TableActions.selectAll(_id);
          }
          break;
        // TODO
        case "DELETE":
          // delete
          if (selectedRows.length && !TableStore.performingAnyAction(_id)) {
            if (TableStore.getTable(_id).type === "files") {
              const storage = MainFunctions.storageCodeToServiceName(
                FilesListStore.findCurrentStorage().storageType
              );
              if (
                FilesListStore.isAnyShared() &&
                storage !== "onshape" &&
                storage !== "onshapedev" &&
                storage !== "onshapedstaging" &&
                storage !== "sharepoint" &&
                storage !== "samples" &&
                !storage.includes("webdav")
              ) {
                // Trying to delete files/folders shared to current user
                SnackbarUtils.alertError({ id: "sharedDeleteError" });
              } else if (FilesListStore.getCurrentState() === "trash") {
                ModalActions.eraseObjects(
                  TableStore.getSelection(TableStore.getFocusedTable())
                );
              } else {
                ModalActions.deleteObjects(
                  "files",
                  TableStore.getSelection(TableStore.getFocusedTable())
                );
              }
            } else if (TableStore.getTable(_id).type === PUBLIC_TEMPLATES) {
              let templateType = PUBLIC_TEMPLATES;
              if (MainFunctions.detectPageType() === CUSTOM_TEMPLATES) {
                templateType = CUSTOM_TEMPLATES;
              }
              ModalActions.deleteObjects(
                "templates",
                TableStore.getSelection(TableStore.getFocusedTable()),
                templateType
              );
            }
          }
          break;
        case "RENAME":
          // F2
          if (
            !TableStore.performingAnyAction(_id) &&
            selectedRows.length === 1
          ) {
            if (
              (selectedRows[0].type !== "file" &&
                selectedRows[0].type !== "folder") ||
              !selectedRows[0].shared ||
              selectedRows[0].isOwner
            ) {
              TableActions.editField(_id, selectedRows[0].id, "name", true);
            } else {
              SnackbarUtils.alertWarning({
                id: "renameNotPermitted",
                name: selectedRows[0].name
              });
            }
          }
          break;
        case "OPEN": {
          if (selectedRows.length === 1) {
            // Enter
            const { _id: tableId } = this.state;
            const { id: objectId } = selectedRows[0];
            const currentTable = TableStore.getTable(tableId);
            if (currentTable.type === "files") {
              const info = TableStore.getRowInfo(tableId, objectId);
              const ext =
                info.type === "folder"
                  ? "folder"
                  : MainFunctions.getExtensionFromName(info.name);
              const isOpenAvailable =
                (!info.process || info.nonBlockingProcess) &&
                UserInfoStore.extensionSupported(ext, info.mimeType) &&
                FilesListStore.getCurrentState() !== "trash";
              if (isOpenAvailable) {
                FilesListStore.open(objectId);
              }
            }
          }
          break;
        }
        default:
      }
    }
  };

  setJqueryListeners = () => {
    const { _id: currentTableId } = this.state;
    const { getObjects, doesNextPageExist } = this.props;

    const activateLoading = () => {
      this.setState({
        isLoading: true
      });
    };

    // TODO: get rid of jQuery events for styling and removing selection
    MainFunctions.attachJQueryListener($(document), "click", e => {
      const target = $(e.target);
      const flag = !!(
        target.parents(".tableRow").length ||
        target.parent().is(".tableRow") ||
        target.is(".tableRow") ||
        target.is("span") ||
        target.is("a") ||
        target.is(".fade.modal") ||
        target.parents(".dialog").length ||
        target.hasClass("btn") ||
        target.parents(".modal").length ||
        target.is(".contextItem") ||
        target.parents(".contextItem").length ||
        target.parents(".btn").length ||
        target.parents(".btn-group").length ||
        target.is("input")
      );
      if (
        flag === false &&
        TableStore.getSelection(currentTableId).length > 0
      ) {
        TableActions.removeSelection();
      }
    });
    if (TableStore.isTableRegistered(currentTableId)) {
      MainFunctions.attachJQueryListener(
        $("table.tableViewMain tbody"),
        "scroll",
        function NextPageLoader() {
          const isPageToken = doesNextPageExist();
          if (
            isPageToken &&
            $(this)[0].scrollHeight - $(this).scrollTop() - $(this).height() <=
              50 &&
            !TableStore.getTable(currentTableId).loading
          ) {
            getObjects(true);
            activateLoading();
          }
          $(`.MuiTooltip-popper`).css({
            opacity: 0,
            transition: `opacity .1s`
          });
        }
      );
    }
  };

  sortByColumn = type => {
    const { _id } = this.state;
    TableActions.sortList(_id, type);
  };

  render() {
    const { _id, selection, content: table, isLoading } = this.state;
    const selectedIds = _.pluck(selection, "_id");
    let headers = [];
    if (table !== undefined && TableStore.isTableRegistered(_id)) {
      // generate table headers from object

      headers = Object.keys(table.fields).map(fieldKey => (
        <th
          key={fieldKey}
          onClick={this.sortByColumn.bind(this, fieldKey)}
          className={table.orderedBy === fieldKey ? "sorted" : ""}
        >
          <FormattedMessage id={table.fields[fieldKey].caption || fieldKey} />{" "}
          {table.orderedBy === fieldKey ? (
            <StyledSortIcon
              alt={
                table.fields[fieldKey].order === "asc"
                  ? "Ascending"
                  : "Descending"
              }
              src={
                table.fields[fieldKey].order === "asc"
                  ? sortASCSVG
                  : sortDESCSVG
              }
            />
          ) : null}
        </th>
      ));
      const tableEntities = table.results || [];
      const msgId =
        table.type === "users" ? "noUserFound" : "noFilesInCurrentFolder";
      const classId = table.type === "users" ? "noUsers" : "noFiles";
      const { isPadded, gridClassName } = this.props;
      return (
        <Shortcuts
          name="TABLE"
          handler={this.onKeyEvent}
          targetNodeSelector="body"
          global
        >
          <Grid
            container
            fluid
            className={`${isPadded ? "" : "noPadding"} ${gridClassName}`}
          >
            {table.loading === true && !tableEntities.length ? (
              <Loader />
            ) : null}
            <table
              className={`OLD blockable tableViewMain sized${headers.length} ${table.type}`}
              id={_id}
            >
              <thead>
                <tr>{headers}</tr>
              </thead>
              <tbody>
                {tableEntities.map(element => (
                  <TableRow
                    type={table.type}
                    tableId={_id}
                    ref={`tableObject_${element._id}`}
                    key={element._id || element.id}
                    element={element}
                    isSelected={selectedIds.includes(element._id || element.id)}
                  />
                ))}
                {table.loading === false && tableEntities.length === 0 ? (
                  <tr className={classId}>
                    <td className={classId}>
                      <Box
                        sx={{
                          display: "flex",
                          justifyContent: "center",
                          alignItems: "center"
                        }}
                      >
                        <img
                          src={NoFileFoundSVG}
                          style={{ width: "30px", height: "30px" }}
                          alt={msgId}
                        />
                        <span style={{ marginLeft: "8px", marginTop: "3px" }}>
                          {formatMessage({ id: msgId })}
                        </span>
                      </Box>
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
            {isLoading ? <InlineLoader xref={table.type === "xref"} /> : null}
          </Grid>
        </Shortcuts>
      );
    }
    return null;
  }
}

export default injectIntl(TableView);
