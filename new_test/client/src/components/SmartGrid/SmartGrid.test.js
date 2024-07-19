/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import Immutable from "immutable";
import { render } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { configure } from "@testing-library/dom";
import SmartGrid from "./SmartGrid";
import FileEntity from "./grids/files/FileEntity";

configure({
  testIdAttribute: "data-component"
});

const testDataSet1 = Immutable.fromJS([
  {
    name: "test_file_name1",
    updateDate: +new Date(),
    creationDate: +new Date(),
    id: "test_file_id1",
    thumbnail: null,
    mimeType: null,
    public: false,
    permissions: {},
    type: "file"
  },
  {
    name: "test_file_name2",
    updateDate: +new Date(),
    creationDate: +new Date(),
    id: "test_file_id2",
    thumbnail: null,
    mimeType: null,
    public: false,
    permissions: {},
    type: "file"
  },
  {
    name: "test_file_name3",
    updateDate: +new Date(),
    creationDate: +new Date(),
    id: "test_file_id3",
    thumbnail: null,
    mimeType: null,
    public: false,
    permissions: {},
    type: "file"
  },
  {
    name: "test_file_name4",
    updateDate: +new Date(),
    creationDate: +new Date(),
    id: "test_file_id4",
    thumbnail: null,
    mimeType: null,
    public: false,
    permissions: {},
    type: "file"
  },
  {
    name: "test_file_name5",
    updateDate: +new Date(),
    creationDate: +new Date(),
    id: "test_file_id5",
    thumbnail: null,
    mimeType: null,
    public: false,
    permissions: {},
    type: "file"
  },
  {
    name: "test_file_name6",
    updateDate: +new Date(),
    creationDate: +new Date(),
    id: "test_file_id6",
    thumbnail: null,
    mimeType: null,
    public: false,
    permissions: {},
    type: "file"
  }
]);

// Solution for correct render List in test renderer:
// https://github.com/bvaughn/react-virtualized/issues/493
// https://stackoverflow.com/questions/62214833/when-using-react-virtualized-autosizer-children-not-being-rendered-in-test

describe("SmartGrid", () => {
  const originalOffsetHeight = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    "offsetHeight"
  );
  const originalOffsetWidth = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    "offsetWidth"
  );

  beforeAll(() => {
    Object.defineProperty(HTMLElement.prototype, "offsetHeight", {
      configurable: true,
      value: 50
    });
    Object.defineProperty(HTMLElement.prototype, "offsetWidth", {
      configurable: true,
      value: 50
    });
  });

  afterAll(() => {
    Object.defineProperty(
      HTMLElement.prototype,
      "offsetHeight",
      originalOffsetHeight
    );
    Object.defineProperty(
      HTMLElement.prototype,
      "offsetWidth",
      originalOffsetWidth
    );
  });

  test("Is rendered correctly", async () => {
    expect(1).toBe(1);

    // I.A: Disabled console log for this test render, be because
    // only in tests appears error about key prop that can`t be fixed
    // but normal renders looks well
    const err = console.error;
    console.error = () => null;

    const { getAllByTestId, rerender } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGrid
          data={testDataSet1}
          isLoading={false}
          countOfColumns={3}
          gridItem={FileEntity}
          fixedGridHeight={false}
          noDataCaption="noFilesInCurrentFolder"
          setDimensions={() => 500}
        />
      </IntlProvider>
    );

    console.error = err;

    let rows = getAllByTestId("smart-grid-row");
    expect(rows.length).toBe(2);
    expect(rows[0]).toHaveTextContent("test_file_name1");
    expect(rows[0]).toHaveTextContent("test_file_name2");
    expect(rows[0]).toHaveTextContent("test_file_name3");
    expect(rows[1]).toHaveTextContent("test_file_name4");
    expect(rows[1]).toHaveTextContent("test_file_name5");
    expect(rows[1]).toHaveTextContent("test_file_name6");

    rerender(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGrid
          data={testDataSet1}
          isLoading={false}
          countOfColumns={2}
          gridItem={FileEntity}
          fixedGridHeight={false}
          noDataCaption="noFilesInCurrentFolder"
          setDimensions={() => 500}
        />
      </IntlProvider>
    );

    rows = getAllByTestId("smart-grid-row");
    expect(rows.length).toBe(3);

    expect(rows[0]).toHaveTextContent("test_file_name1");
    expect(rows[0]).toHaveTextContent("test_file_name2");
    expect(rows[1]).toHaveTextContent("test_file_name3");
    expect(rows[1]).toHaveTextContent("test_file_name4");
    expect(rows[2]).toHaveTextContent("test_file_name5");
    expect(rows[2]).toHaveTextContent("test_file_name6");
    rerender(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <SmartGrid
          data={testDataSet1}
          isLoading={false}
          countOfColumns={1}
          gridItem={FileEntity}
          fixedGridHeight={false}
          noDataCaption="noFilesInCurrentFolder"
          setDimensions={() => 500}
        />
      </IntlProvider>
    );

    rows = getAllByTestId("smart-grid-row");
    expect(rows.length).toBe(6);

    expect(rows[0]).toHaveTextContent("test_file_name1");
    expect(rows[1]).toHaveTextContent("test_file_name2");
    expect(rows[2]).toHaveTextContent("test_file_name3");
    expect(rows[3]).toHaveTextContent("test_file_name4");
    expect(rows[4]).toHaveTextContent("test_file_name5");
    expect(rows[5]).toHaveTextContent("test_file_name6");
  });
});
