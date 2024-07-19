/* eslint-disable no-unused-vars */
/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import { fireEvent, render, screen, queries } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import KudoSwitch from "./KudoSwitch";
import * as additionalQueries from "../../../utils/test-utils";
import FormManagerActions from "../../../actions/FormManagerActions";
import formManagerStore from "../../../stores/FormManagerStore";

describe("Regular switch", () => {
  test("Is rendered", async () => {
    const { getByRole } = render(
      <KudoSwitch label="TEST" id="test" name="test" translateLabel={false} />
    );
    const checkbox = getByRole("checkbox");
    expect(checkbox).toBeInTheDocument();
    expect(checkbox.checked).toBe(false);
    const label = await screen.findByText("TEST");
    expect(label).toBeInTheDocument();
  });

  test("Is checked by props", () => {
    const { getByRole } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        defaultChecked
      />
    );
    const checkbox = getByRole("checkbox");
    expect(checkbox).toBeInTheDocument();
    expect(checkbox.checked).toBe(true);
  });

  test("data-component is passed", () => {
    const { queryByDataComponent } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        dataComponent="test"
      />,
      {
        queries: {
          ...queries,
          queryByDataComponent: additionalQueries.queryByDataComponent
        }
      }
    );
    const dataComponentComponent = queryByDataComponent("test");
    expect(dataComponentComponent).toHaveAttribute("data-component", "test");
  });

  test("Custom onChange handler is called", () => {
    const onChange = jest.fn();
    const { getByRole } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        onChange={onChange}
      />
    );
    const checkbox = getByRole("checkbox");
    fireEvent.click(checkbox);
    expect(checkbox.checked).toBe(true);
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  test("Custom icon is shown", () => {
    const iconSrc = "https://example.com/";
    const { getByRole } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        iconSrc={iconSrc}
      />
    );
    const image = getByRole("img");
    expect(image).toBeInTheDocument();
    expect(image.src).toBe(iconSrc);
  });
  test("Is disabled by props", () => {
    const { getByRole } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        disabled
      />
    );
    const checkbox = getByRole("checkbox");
    expect(checkbox.disabled).toBe(true);
  });
  test("Re-rendered on styles change", () => {
    const { getByRole, rerender } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        styles={{ switch: { backgroundColor: "red" } }}
      />
    );
    const initialCheckboxClasses = getByRole("switch").className;

    rerender(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        styles={{ switch: { backgroundColor: "red" } }}
      />
    );
    expect(initialCheckboxClasses).toEqual(getByRole("switch").className);
    rerender(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        styles={{ switch: { backgroundColor: "green" } }}
      />
    );
    expect(initialCheckboxClasses).not.toEqual(getByRole("switch").className);
  });

  test("Should contain class", () => {
    const classNameToAdd = "test-class";
    const { getByRole } = render(
      <KudoSwitch
        label="TEST"
        id="test"
        name="test"
        translateLabel={false}
        className={classNameToAdd}
      />
    );
    const switchClasses = getByRole("group").className;
    expect(switchClasses).toContain(classNameToAdd);
  });

  test("Should translate label", () => {
    const { getByLabelText } = render(
      <IntlProvider locale="en" messages={{ messageKey: "messageValue" }}>
        <KudoSwitch label="messageKey" id="test" name="test" />
      </IntlProvider>
    );
    const label = getByLabelText("messageValue");
    expect(label).toBeInTheDocument();
  });
});
