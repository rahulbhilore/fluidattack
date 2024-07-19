import React from "react";
import PropTypes from "prop-types";
import Tabs from "@material-ui/core/Tabs";
import Tab from "@material-ui/core/Tab";
import Typography from "@material-ui/core/Typography";
import { withStyles, makeStyles } from "@material-ui/core/styles";
import { FormattedMessage } from "react-intl";
import MainFunctions from "../../../../libraries/MainFunctions";

function TabPanel(props) {
  const { children, value, index } = props;

  return (
    <Typography
      component="div"
      role="tabpanel"
      hidden={value !== index}
      id={`simple-tabpanel-${index}`}
      aria-labelledby={`simple-tab-${index}`}
    >
      {value === index && children}
    </Typography>
  );
}

TabPanel.defaultProps = {
  children: null
};

TabPanel.propTypes = {
  children: PropTypes.node,
  index: PropTypes.number.isRequired,
  value: PropTypes.number.isRequired
};

const useStyles = makeStyles(theme => ({
  root: {
    flexGrow: 1
  },
  padding: {
    padding: theme.spacing(3)
  },
  demo1: {
    backgroundColor: theme.palette.LIGHT
  }
}));

const AntTabs = withStyles({
  root: {
    borderBottom: "1px solid #CFCFCF"
  },
  indicator: {
    backgroundColor: "#124daf",
    height: 4
  }
})(Tabs);

const AntTab = withStyles(theme => ({
  root: {
    textTransform: "none",
    minWidth: 140,
    minHeight: 45,
    padding: 0,
    fontSize: theme.typography.pxToRem(12),
    "&:hover": {
      color: "#124daf",
      opacity: 1
    },
    "&$selected": {
      color: "#124daf",
      fontWeight: theme.typography.fontWeightBold
    },
    "&:focus": {
      color: "#124daf"
    }
  },
  wrapper: {
    flexDirection: "row",
    paddingRight: 10,
    paddingLeft: 10,
    "& img": {
      marginRight: 5,
      height: "25px",
      maxWidth: "25px",
      marginBottom: "0 !important"
    }
  },
  selected: {}
  // eslint-disable-next-line react/jsx-props-no-spreading
}))(props => <Tab disableRipple {...props} />);

export default function PermissionTabs({
  productIconUrl,
  storage,
  storageIcon,
  children,
  tabNumber,
  passTabNumber,
  internalAccess,
  publicAccess
}) {
  const classes = useStyles();
  const maxTabNumber = internalAccess + publicAccess - 1;
  const [value, setValue] = React.useState(
    tabNumber > maxTabNumber ? maxTabNumber : tabNumber
  );

  const handleChange = (event, newValue) => {
    if (newValue <= maxTabNumber) {
      setValue(newValue);
      passTabNumber(newValue);
    }
  };

  const condensedChildren = children.filter(child => child !== null);
  return (
    <div className={classes.root}>
      <div className={classes.demo1}>
        <AntTabs value={value} onChange={handleChange} aria-label="ant example">
          {publicAccess ? (
            <AntTab
              label={<FormattedMessage id="sharingLink" />}
              icon={<img src={productIconUrl} alt={storage} />}
              data-component="view-only-tab"
            />
          ) : null}
          {internalAccess ? (
            <AntTab
              label={
                <FormattedMessage
                  id="storageAccess"
                  values={{
                    storage: MainFunctions.serviceStorageNameToEndUser(storage)
                  }}
                />
              }
              icon={<img src={storageIcon} alt={storage} />}
              data-component="gdrive-tab"
            />
          ) : null}
        </AntTabs>
      </div>
      {condensedChildren.map((child, index) => (
        // eslint-disable-next-line react/no-array-index-key
        <TabPanel key={`tab_${index}`} value={value} index={index}>
          {child}
        </TabPanel>
      ))}
    </div>
  );
}

PermissionTabs.propTypes = {
  productIconUrl: PropTypes.string.isRequired,
  storage: PropTypes.string.isRequired,
  storageIcon: PropTypes.string.isRequired,
  children: PropTypes.node.isRequired,
  passTabNumber: PropTypes.func,
  tabNumber: PropTypes.number,
  publicAccess: PropTypes.bool,
  internalAccess: PropTypes.bool
};

PermissionTabs.defaultProps = {
  passTabNumber: () => null,
  tabNumber: 0,
  publicAccess: true,
  internalAccess: true
};
