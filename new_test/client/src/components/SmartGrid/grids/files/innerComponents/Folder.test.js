/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import _ from "underscore";
import { fireEvent, render } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { configure } from "@testing-library/dom";
import iconsDictionary from "../../../../../constants/appConstants/ObjectIcons";
import Folder from "./Folder";

configure({
  testIdAttribute: "data-component"
});

const testDataProp = {
  name: "test_folder_name",
  updateDate: +new Date(),
  creationDate: +new Date(),
  id: "test_folder_id",
  thumbnail: null,
  mimeType: null,
  public: false,
  permissions: {},
  isShared: false
};

describe("SmartGrid Folder component", () => {
  test("Is rendered correctly", () => {
    const { getByTestId } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <Folder
          data={testDataProp}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
        />
      </IntlProvider>
    );

    const container = getByTestId("folder-container");
    expect(container).toBeInTheDocument();
    expect(container).toHaveTextContent("test_folder_name");
  });

  test("folder image", () => {
    const { getByAltText } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <Folder
          data={testDataProp}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
        />
      </IntlProvider>
    );

    expect(getByAltText(testDataProp.name).src).toContain(
      iconsDictionary.folderSVG
    );
  });

  test("Shared folder image", () => {
    const newDataTestProp = _.clone(testDataProp);
    newDataTestProp.isShared = true;

    const { getByAltText } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <Folder
          data={newDataTestProp}
          showMenu={() => null}
          width={200}
          openLink="test_link"
          onLinkClick={() => null}
        />
      </IntlProvider>
    );

    expect(getByAltText(testDataProp.name).src).toContain(
      iconsDictionary.folderSharedSVG
    );
  });

  // test("context menu click", () => {
  //   const showMenu = jest.fn();

  //   const { getAllByRole } = render(
  //     <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
  //       <Folder
  //         data={testDataProp}
  //         showMenu={showMenu}
  //         width={200}
  //         openLink="test_link"
  //         onLinkClick={() => null}
  //       />
  //     </IntlProvider>
  //   );

  //   const buttons = getAllByRole("button");
  //   fireEvent.click(buttons[1]);
  //   expect(showMenu).toHaveBeenCalledTimes(1);
  // });
});
