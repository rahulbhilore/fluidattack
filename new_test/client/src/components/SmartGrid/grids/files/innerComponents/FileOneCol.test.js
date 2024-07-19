/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import { fireEvent, render } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { configure } from "@testing-library/dom";
import FileOneCol from "./FileOneCol";

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
  permissions: {}
};

describe("SmartGrid FileOneCol component", () => {
  test("Is rendered correctly", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <FileOneCol
          data={testDataProp}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          isSharingAllowed
        />
      </IntlProvider>
    );

    const container = getByTestId("file-one-col-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_file_name");
    expect(container).toHaveTextContent("now");
  });

  test("context menu click", () => {
    const showMenu = jest.fn();

    const { getAllByRole } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <FileOneCol
          data={testDataProp}
          showMenu={showMenu}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
          isSharingAllowed
        />
      </IntlProvider>
    );

    const buttons = getAllByRole("button");
    // fireEvent.click(buttons[1]);
    // expect(showMenu).toHaveBeenCalledTimes(1);
  });
});
