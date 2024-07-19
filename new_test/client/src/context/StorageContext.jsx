import React from "react";

const StorageContext = React.createContext({
  storage: "",
  account: "",
  objectId: ""
});

export default StorageContext;
