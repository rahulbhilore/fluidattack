/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import _ from "underscore";
import { render } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { configure } from "@testing-library/dom";
import SmartGridCell from "./SmartGridCell";
import FileEntity from "./grids/files/FileEntity";

configure({
  testIdAttribute: "data-component"
});

const testDataProp = {
  name: "test_file_name",
  updateDate: +new Date(),
  creationDate: +new Date(),
  id: "test_file_id",
  thumbnail: null,
  mimeType: null,
  public: false,
  permissions: {},
  type: "file"
};

describe("SmartGridCell", () => {
  test("Is render correctly", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGridCell
          data={testDataProp}
          countOfColumns={1}
          gridItem={FileEntity}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
          getGetter={() => null}
        />
      </IntlProvider>
    );

    const container = getByTestId("smart-grid-cell");
    expect(container).toBeInTheDocument();
  });

  test("Is render correctly for file and one col", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGridCell
          data={testDataProp}
          countOfColumns={1}
          gridItem={FileEntity}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
          getGetter={() => null}
        />
      </IntlProvider>
    );

    const container = getByTestId("file-one-col-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_file_name");
  });

  test("Is render correctly for file and many cols", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGridCell
          data={testDataProp}
          countOfColumns={3}
          gridItem={FileEntity}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
          getGetter={() => null}
        />
      </IntlProvider>
    );

    const container = getByTestId("file-many-cols-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_file_name");
  });

  test("Is render correctly for folder", () => {
    const newDataTestProp = _.clone(testDataProp);
    newDataTestProp.type = "folder";
    newDataTestProp.name = "test_folder_name";

    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGridCell
          data={newDataTestProp}
          countOfColumns={1}
          gridItem={FileEntity}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
          getGetter={() => null}
        />
      </IntlProvider>
    );

    const container = getByTestId("folder-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_folder_name");
  });
});
