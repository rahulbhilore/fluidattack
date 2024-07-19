import React from "react";
import PropTypes from "prop-types";
import { makeStyles } from "@material-ui/core/styles";
import { supportedApps } from "../../stores/UserInfoStore";

const useStyles = makeStyles({
  form: {
    display: "none",
    height: 0,
    width: 0,
    position: "absolute",
    top: 0,
    left: 0
  },
  input: {
    display: "none"
  }
});

export default function UploadForm(props) {
  const classes = useStyles();
  const { onChange } = props;

  const inputHandler = e => {
    onChange(e);
    e.target.value = "";
  };

  return (
    <form className={classes.form}>
      <input
        id="dwgupload"
        className={classes.input}
        name="file"
        type="file"
        accept={`${supportedApps.xenon
          .map(v => `.${v}`)
          .join(",")},application/acad,image/vnd.dwg,image/vnd.dwt`}
        multiple
        onChange={inputHandler}
      />
    </form>
  );
}

UploadForm.propTypes = {
  onChange: PropTypes.func.isRequired
};
