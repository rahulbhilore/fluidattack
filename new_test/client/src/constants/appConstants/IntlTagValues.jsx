import React from "react";

export const strong = msg => <strong>{msg}</strong>;
export const del = msg => <del>{msg}</del>;
export const br = <br />;
export const link = (msg, href) => (
  <a href={href} target="_blank" rel="noopener noreferrer">
    {msg}
  </a>
);
