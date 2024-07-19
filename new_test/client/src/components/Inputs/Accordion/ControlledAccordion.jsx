import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { makeStyles } from "@material-ui/core/styles";
import Accordion from "@material-ui/core/Accordion";
import AccordionDetails from "@material-ui/core/AccordionDetails";
import AccordionSummary from "@material-ui/core/AccordionSummary";
import Typography from "@material-ui/core/Typography";
import ExpandMoreIcon from "@material-ui/icons/ExpandMore";

const useStyles = makeStyles(theme => ({
  root: {
    width: "100%",
    backgroundColor: "white"
  },
  icon: {
    color: "black"
  },
  heading: {
    fontSize: theme.typography.pxToRem(15),
    flexBasis: "33.33%",
    flexShrink: 0
  },
  children: {
    display: "block"
  }
}));

export default function ControlledAccordion({
  children,
  title,
  preserveChildrenRender,
  classes,
  dataComponent,
  dataName
}) {
  const internalClasses = useStyles();
  const [expanded, setExpanded] = React.useState(false);
  const [preserved, setPreserved] = React.useState(false);

  const handleChange = () => {
    if (!preserved) setPreserved(true);
    setExpanded(!expanded);
  };

  return (
    <Accordion
      expanded={expanded}
      onChange={handleChange}
      className={internalClasses.root}
      data-component={dataComponent || "accordion"}
      data-name={dataName || "null"}
    >
      <AccordionSummary
        expandIcon={<ExpandMoreIcon className={internalClasses.icon} />}
      >
        <Typography className={clsx(internalClasses.heading, classes.heading)}>
          {title}
        </Typography>
      </AccordionSummary>
      <AccordionDetails className={internalClasses.children}>
        {expanded || (preserveChildrenRender && preserved) ? children : null}
      </AccordionDetails>
    </Accordion>
  );
}

ControlledAccordion.propTypes = {
  children: PropTypes.node.isRequired,
  title: PropTypes.oneOfType([PropTypes.string, PropTypes.node]).isRequired,
  preserveChildrenRender: PropTypes.bool,
  classes: PropTypes.shape({
    heading: PropTypes.string
  }),
  dataComponent: PropTypes.string,
  dataName: PropTypes.string
};

ControlledAccordion.defaultProps = {
  preserveChildrenRender: false,
  classes: {
    heading: ""
  },
  dataComponent: "",
  dataName: ""
};
