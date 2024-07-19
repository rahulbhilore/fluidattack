import React from "react";
import { Link } from "react-router";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Box } from "@material-ui/core";
import DialogBody from "../DialogBody";
import kudoLogo from "../../../assets/images/kudo-logo-small.svg";
import ModalActions from "../../../actions/ModalActions";
import applicationStore from "../../../stores/ApplicationStore";

const useStyles = makeStyles(theme => ({
  body: {
    padding: 0
  },
  text: {
    color: theme.palette.DARK,
    userSelect: "text",
    marginBottom: theme.spacing(1),
    textAlign: "justify"
  },
  logoBox: {
    display: "flex",
    justifyContent: "space-between",
    backgroundColor: theme.palette.JANGO,
    padding: theme.spacing(2),
    marginBottom: theme.spacing(2),
    borderTop: `solid 1px ${theme.palette.REY}`
  },
  logo: {
    height: "50px"
  },
  textBlock: {
    padding: theme.spacing(0, 2, 1)
  },
  revisionText: {
    alignItems: "center",
    display: "flex",
    color: theme.palette.REY
  }
}));

export default function showAbout() {
  const classes = useStyles();
  const handleClose = () => {
    ModalActions.hide();
  };
  return (
    <DialogBody className={classes.body}>
      <Box className={classes.logoBox}>
        <img src={kudoLogo} alt="ARES Kudo" className={classes.logo} />
        <Typography variant="body2" className={classes.revisionText}>
          v.{applicationStore.getApplicationSetting("revision")}
        </Typography>
      </Box>
      <div className={classes.textBlock}>
        <Typography className={classes.text}>
          This application incorporates ARES® Kudo software pursuant to a
          license agreement with Gräbert GmbH © 2004 -{" "}
          {new Date().getFullYear()} (www.graebert.com).
        </Typography>
        <Typography className={classes.text}>
          Portions of this software © 2018 The Qt Company Ltd.
        </Typography>
        <Typography className={classes.text}>
          This product includes software developed by the OpenSSL Project (for
          use in the OpenSSL Toolkit. (http://www.openssl.org/)
        </Typography>
        <Typography className={classes.text}>
          Portions of this software © 1992-2016 Peter Gutmann. All rights
          reserved
        </Typography>
        <Typography className={classes.text}>
          Portions of this software © 1989-2001 by SINTEF Applied Mathematics,
          Oslo, Norway. All Rights Reserved.
        </Typography>
        <Typography className={classes.text}>
          This application incorporates Teigha® software pursuant to a license
          agreement with Open Design Alliance. Teigha® Copyright © 2002-2017
          by Open Design Alliance. All rights reserved.
        </Typography>
        <Typography className={classes.text}>
          3D input device development tools and related technology are provided
          under license from 3Dconnexion. © 3Dconnexion 1992 - 2018. All rights
          reserved.
        </Typography>
        <Typography className={classes.text}>
          <Link to="/licenses" onClick={handleClose}>
            Open source software components
          </Link>{" "}
          used in the ARES Kudo.
        </Typography>
      </div>
    </DialogBody>
  );
}
