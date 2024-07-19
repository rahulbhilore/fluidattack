import React, { useState } from "react";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Storage from "../../../utils/Storage";
// import * as RequestMethods from "../../../constants/appConstants/RequestsMethods";
import Loader from "../../Loader";
import DialogBody from "../DialogBody";

const useStyles = makeStyles(() => ({
  root: {
    padding: 0,
    alignItems: "center",
    justifyContent: "center",
    display: "flex"
  },
  image: {
    maxWidth: "100%"
  }
}));

export default function showImage({ info }) {
  const [src, setSrc] = useState(null);
  // const headers = Requests.getDefaultUserHeaders();
  // headers["Content-Type"] = "arraybuffer";
  // Requests.sendGenericRequest(
  //   `/files/${info.id}/data`,
  //   RequestMethods.GET,
  //   headers
  // ).then(data => {
  //   console.info(data);
  // });
  // TODO - replace with Requests call
  const oReq = new XMLHttpRequest();
  oReq.open(
    "GET",
    `${window.ARESKudoConfigObject.api}/files/${info.id}/data`,
    true
  );
  oReq.setRequestHeader("sessionId", Storage.store("sessionId"));
  oReq.setRequestHeader("locale", Storage.store("locale"));
  oReq.responseType = "arraybuffer";
  oReq.onload = () => {
    const blob = new Blob([oReq.response]);
    const reader = new window.FileReader();
    reader.readAsDataURL(blob);
    reader.onloadend = () => {
      setSrc(reader.result);
    };
  };
  oReq.send();
  const { name } = info;
  const classes = useStyles();
  return (
    <DialogBody className={classes.root}>
      {src ? (
        <img className={classes.image} src={src} alt={name} />
      ) : (
        <Loader isModal />
      )}
    </DialogBody>
  );
}
