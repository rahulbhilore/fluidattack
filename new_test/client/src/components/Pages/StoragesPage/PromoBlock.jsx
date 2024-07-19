import React from "react";
import PropTypes from "prop-types";
import { Typography } from "@material-ui/core";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import StorageIcons from "../../../constants/appConstants/StorageIcons";

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.LIGHT,
    border: `1px solid #e9e9e9`,
    padding: theme.spacing(4)
  },
  img: {
    width: "30px",
    margin: theme.spacing(1, 3)
  },
  caption: {
    textAlign: "center",
    color: theme.palette.JANGO,
    fontSize: ".8rem",
    margin: theme.spacing(2)
  },
  images: {
    textAlign: "center"
  }
}));

export default function PromoBlock({ availableStorages }) {
  const classes = useStyles();
  const scrollToPosition = (event, elementId) => {
    const elementToScroll = document.getElementById(elementId);
    const header = document.getElementsByTagName("header");
    if (elementToScroll && elementToScroll.offsetTop && header && header[0]) {
      event.preventDefault();
      const offset = elementToScroll.offsetTop;
      const headerHeight = header[0].clientHeight;
      document.getElementById("storagesPage").scroll(0, offset - headerHeight);
    }
  };
  return (
    <div className={classes.root} data-component="storagesPromoBlock">
      <Typography variant="h3" className={classes.caption}>
        <FormattedMessage id="connectYourFavoriteStorage" />
      </Typography>
      <div className={classes.images}>
        {availableStorages.map(storageObject => (
          <a
            key={storageObject.name.toLowerCase()}
            href={`#storage_${storageObject.name.toLowerCase()}`}
            onClick={e => {
              scrollToPosition(
                e,
                `storage_${storageObject.name.toLowerCase()}`
              );
            }}
          >
            <img
              className={classes.img}
              key={`storageIcon_${storageObject.name}`}
              alt={storageObject.name}
              src={StorageIcons[`${storageObject.name}ActiveSVG`]}
            />
          </a>
        ))}
      </div>
    </div>
  );
}

PromoBlock.propTypes = {
  availableStorages: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired
    })
  ).isRequired
};
