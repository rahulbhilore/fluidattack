/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import { render } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { configure } from "@testing-library/dom";
import { AutoSizer, CellMeasurerCache, List } from "react-virtualized";
import SmartGridRow from "./SmartGridRow";
import FileEntity from "./grids/files/FileEntity";

configure({
  testIdAttribute: "data-component"
});

const cache = new CellMeasurerCache({
  isLoading: false,
  defaultHeight: 50,
  fixedWidth: true
});

const testDataSetOneColumn = [
  [
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
    }
  ],
  [
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
    }
  ],
  [
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
    }
  ]
];

const testDataSetTwoColumns = [
  [
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
    }
  ],
  [
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
    }
  ],
  [
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
  ]
];

const testDataSetThreeColumns = [
  [
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
    }
  ],
  [
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
  ]
];

describe("SmartGridRow", () => {
  test("Is render correctly for 1 column", () => {
    // I.A: Disabled console log for this test render, be because
    // only in tests appears error about key prop that can`t be fixed
    // but normal renders looks well
    const err = console.error;
    console.error = () => null;

    const { getAllByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <AutoSizer disableHeight>
          {() => (
            <List
              width={600}
              height={500}
              rowCount={3}
              rowHeight={300}
              rowRenderer={({
                key, // Unique key within array of rows
                index, // Index of row within collection
                // isScrolling, // The List is currently being scrolled
                // isVisible, // This row is visible within the List (eg it is not an overscanned row)
                style, // Style object to be applied to row (to position it),
                parent
              }) => (
                <SmartGridRow
                  cache={cache}
                  columnIndex={0}
                  key={key}
                  parent={parent}
                  rowIndex={index}
                  style={style}
                  data={testDataSetOneColumn[index]}
                  countOfColumns={testDataSetOneColumn.length}
                  gridItem={FileEntity}
                  width={600}
                  gridId="grid_id"
                  getGetter={() => {}}
                />
              )}
              noRowsRenderer={() => null}
            />
          )}
        </AutoSizer>
      </IntlProvider>
    );
    console.error = err;

    const rows = getAllByTestId("smart-grid-row");
    expect(rows.length).toBe(3);
    expect(rows[0]).toHaveTextContent("test_file_name1");
    expect(rows[1]).toHaveTextContent("test_file_name2");
    expect(rows[2]).toHaveTextContent("test_file_name3");
  });

  test("Is render correctly for 2 columns", () => {
    const err = console.error;
    console.error = () => null;

    const { getAllByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <AutoSizer disableHeight>
          {() => (
            <List
              width={600}
              height={500}
              rowCount={3}
              rowHeight={300}
              rowRenderer={({
                key, // Unique key within array of rows
                index, // Index of row within collection
                // isScrolling, // The List is currently being scrolled
                // isVisible, // This row is visible within the List (eg it is not an overscanned row)
                style, // Style object to be applied to row (to position it),
                parent
              }) => (
                <SmartGridRow
                  cache={cache}
                  columnIndex={0}
                  key={key}
                  parent={parent}
                  rowIndex={index}
                  style={style}
                  data={testDataSetTwoColumns[index]}
                  countOfColumns={testDataSetTwoColumns.length}
                  gridItem={FileEntity}
                  width={600}
                  gridId="grid_id"
                  getGetter={() => {}}
                />
              )}
              noRowsRenderer={() => null}
            />
          )}
        </AutoSizer>
      </IntlProvider>
    );
    console.error = err;

    const rows = getAllByTestId("smart-grid-row");
    expect(rows.length).toBe(3);
    expect(rows[0]).toHaveTextContent("test_file_name1");
    expect(rows[0]).toHaveTextContent("test_file_name2");

    expect(rows[1]).toHaveTextContent("test_file_name3");
    expect(rows[1]).toHaveTextContent("test_file_name4");

    expect(rows[2]).toHaveTextContent("test_file_name5");
    expect(rows[2]).toHaveTextContent("test_file_name6");
  });

  test("Is render correctly for 3 columns", () => {
    const err = console.error;
    console.error = () => null;

    const { getAllByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <AutoSizer disableHeight>
          {() => (
            <List
              width={600}
              height={500}
              rowCount={2}
              rowHeight={300}
              rowRenderer={({
                key, // Unique key within array of rows
                index, // Index of row within collection
                // isScrolling, // The List is currently being scrolled
                // isVisible, // This row is visible within the List (eg it is not an overscanned row)
                style, // Style object to be applied to row (to position it),
                parent
              }) => (
                <SmartGridRow
                  cache={cache}
                  columnIndex={0}
                  key={key}
                  parent={parent}
                  rowIndex={index}
                  style={style}
                  data={testDataSetThreeColumns[index]}
                  countOfColumns={testDataSetThreeColumns.length}
                  gridItem={FileEntity}
                  width={600}
                  gridId="grid_id"
                  getGetter={() => {}}
                />
              )}
              noRowsRenderer={() => null}
            />
          )}
        </AutoSizer>
      </IntlProvider>
    );
    console.error = err;

    const rows = getAllByTestId("smart-grid-row");
    expect(rows.length).toBe(2);
    expect(rows[0]).toHaveTextContent("test_file_name1");
    expect(rows[0]).toHaveTextContent("test_file_name2");
    expect(rows[0]).toHaveTextContent("test_file_name3");

    expect(rows[1]).toHaveTextContent("test_file_name4");
    expect(rows[1]).toHaveTextContent("test_file_name5");
    expect(rows[1]).toHaveTextContent("test_file_name6");
  });
});
