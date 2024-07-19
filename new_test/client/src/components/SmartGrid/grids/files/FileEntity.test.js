/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import _ from "underscore";
import { render } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { configure } from "@testing-library/dom";
import FileEntity from "./FileEntity";

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

describe("SmartGrid FileEntity component", () => {
  test("Is rendered correctly for file one col", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <FileEntity
          data={testDataProp}
          countOfColumns={1}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
        />
      </IntlProvider>
    );

    const container = getByTestId("file-one-col-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_file_name");
  });

  test("Is rendered correctly for file many cols", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <FileEntity
          data={testDataProp}
          countOfColumns={2}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
        />
      </IntlProvider>
    );

    const container = getByTestId("file-many-cols-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_file_name");
  });

  test("Is rendered correctly for folder", () => {
    const newDataTestProp = _.clone(testDataProp);
    newDataTestProp.type = "folder";

    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <FileEntity
          data={newDataTestProp}
          countOfColumns={2}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          gridId="test_grid_id"
        />
      </IntlProvider>
    );

    const container = getByTestId("folder-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_file_name");
  });
});
