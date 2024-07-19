/* eslint-disable react/jsx-filename-extension */
/* eslint-disable no-undef */
import React from "react";
import { fireEvent, render } from "@testing-library/react";
import KudoButton from "./KudoButton";
import FormManagerActions from "../../../actions/FormManagerActions";
import formManagerStore from "../../../stores/FormManagerStore";

describe("Regular button", () => {
  test("Is rendered", () => {
    const { getByRole } = render(<KudoButton>TEST</KudoButton>);
    const button = getByRole("button");
    expect(button).toBeInTheDocument();
    expect(button).toHaveTextContent("TEST");
    expect(button).toBeDisabled();
  });

  test("Is enabled by props", () => {
    const { getByRole } = render(
      <KudoButton isDisabled={false}>TEST</KudoButton>
    );
    const button = getByRole("button");
    expect(button).toBeEnabled();
  });

  test("Is submit", () => {
    const { getByRole } = render(<KudoButton isSubmit>SUBMIT</KudoButton>);
    const button = getByRole("button");
    expect(button).toHaveAttribute("type", "submit");
    expect(button).toHaveAttribute("data-component", "submit-button");
  });

  test("data-component is passed", () => {
    const { getByRole } = render(
      <KudoButton dataComponent="test">ID</KudoButton>
    );
    const button = getByRole("button");
    expect(button).toHaveAttribute("data-component", "test");
  });

  test("Custom onClick handler is called", () => {
    const onClick = jest.fn();
    const { getByRole } = render(
      <KudoButton isDisabled={false} onClick={onClick}>
        Test
      </KudoButton>
    );
    fireEvent.click(getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  test("Form events are handled", () => {
    formManagerStore.registerForm("form");
    const { getByRole } = render(
      <KudoButton formId="form" isSubmit isDisabled>
        TEST
      </KudoButton>
    );
    const button = getByRole("button");
    expect(button).toBeDisabled();
    FormManagerActions.changeButtonState("form", `formButton_form`, false);
    expect(button).toBeDisabled();
    FormManagerActions.changeButtonState("form", `formButton_form`, true);
    expect(button).toBeEnabled();
    FormManagerActions.changeButtonState("form", `formButton_form`, false);
    expect(button).toBeDisabled();
  });

  test("Re-rendered on styles change", () => {
    const { getByRole, rerender } = render(
      <KudoButton styles={{ button: { backgroundColor: "red" } }}>
        TEST
      </KudoButton>
    );
    const buttonClasses = getByRole("button").className;
    rerender(
      <KudoButton styles={{ button: { backgroundColor: "red" } }}>
        TEST
      </KudoButton>
    );
    expect(buttonClasses).toEqual(getByRole("button").className);
    rerender(
      <KudoButton styles={{ button: { backgroundColor: "green" } }}>
        TEST
      </KudoButton>
    );
    expect(buttonClasses).not.toEqual(getByRole("button").className);
  });

  test("Custom onClick handler isn't called for form button", () => {
    formManagerStore.registerForm("form");
    const onClick = jest.fn();
    const { getByRole } = render(
      <KudoButton
        isDisabled={false}
        onClick={onClick}
        formId="form"
        id="button"
        isSubmit
      >
        Test
      </KudoButton>
    );
    fireEvent.click(getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(0);
    expect(formManagerStore.getForm);
  });

  test("no onClick handler is executed", () => {
    const onClick = jest.fn();
    const { getByRole } = render(
      <KudoButton isDisabled={false}>Test</KudoButton>
    );
    fireEvent.click(getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(0);
  });

  test("Should contain class", () => {
    const classNameToAdd = "test-class";
    const { getByRole } = render(
      <KudoButton className={classNameToAdd}>TEST</KudoButton>
    );
    const buttonClasses = getByRole("button").className;
    expect(buttonClasses).toContain(classNameToAdd);
  });
});
