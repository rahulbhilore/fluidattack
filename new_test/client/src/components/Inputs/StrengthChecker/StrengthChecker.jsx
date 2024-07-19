import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import { Grid } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";

const useStyles = makeStyles(theme => ({
  root: {
    margin: theme.spacing(1, 0, 0, 0),
    padding: 0,
    width: "100%",
    fontSize: theme.typography.pxToRem(12),
    "&.Medium": {
      color: "#e49b24"
    },
    "&.High": {
      color: "#abce7c"
    },
    "&.Low": {
      color: theme.palette.KYLO
    }
  },
  segment: {
    height: ".3rem",
    paddingRight: theme.spacing(0.5)
  },
  segmentFill: {
    height: "100%",
    "&.Medium": {
      backgroundColor: "#e49b24"
    },
    "&.High": {
      backgroundColor: "#abce7c"
    },
    "&, &.inactive": {
      backgroundColor: theme.palette.REY
    },
    "&.Low": {
      backgroundColor: theme.palette.KYLO
    }
  },
  caption: {
    textAlign: "center"
  }
}));

export default function StrengthChecker({ value, onChange, classes }) {
  const [complexityPoints, setPoints] = useState(-1);

  const complexityPointsToSegmentsNumber = () => {
    if (complexityPoints < 50) {
      return 0;
    }
    if (complexityPoints < 70) {
      return 1;
    }
    if (complexityPoints < 100) {
      return 2;
    }
    if (complexityPoints >= 100) {
      return 3;
    }
    return 0;
  };

  const calculateStrength = () => {
    const newComplexityPoints = StrengthChecker.validateValue(value);
    setPoints(newComplexityPoints);
    onChange(newComplexityPoints);
  };

  const complexityPointsToString = () => {
    if (complexityPoints < 50) {
      return "inactive";
    }
    if (complexityPoints < 70) {
      return "Low";
    }
    if (complexityPoints < 100) {
      return "Medium";
    }
    if (complexityPoints >= 100) {
      return "High";
    }
    return "inactive";
  };

  useEffect(() => {
    calculateStrength();
  }, [value]);

  const defaultClasses = useStyles();

  const numberOfSegments = complexityPointsToSegmentsNumber();
  const complexityString = complexityPointsToString();
  const segments = [];
  for (let i = 1; i < 4; i += 1) {
    segments.push(
      <Grid item xs={4} key={`segment_${i}`} className={defaultClasses.segment}>
        <div
          className={clsx(
            defaultClasses.segmentFill,
            numberOfSegments >= i ? complexityString : "inactive"
          )}
        />
      </Grid>
    );
  }

  if (complexityPoints === -1) return null;
  return (
    <Grid
      container
      className={clsx(defaultClasses.root, complexityString, classes.root)}
    >
      {segments}
      {numberOfSegments > 0 ? (
        <>
          <Grid item xs={(numberOfSegments - 1) * 4} />
          <Grid item xs={4} className={defaultClasses.caption}>
            <FormattedMessage id={complexityString.toLowerCase()} />
          </Grid>
        </>
      ) : null}
    </Grid>
  );
}

StrengthChecker.propTypes = {
  value: PropTypes.string,
  onChange: PropTypes.func,
  classes: PropTypes.shape({ root: PropTypes.objectOf(PropTypes.string) })
};

StrengthChecker.defaultProps = {
  value: "",
  onChange: () => null,
  classes: {}
};

StrengthChecker.validateValue = value => {
  const { length } = value;
  const num = {
    Upper: 0,
    Numbers: 0,
    Symbols: 0,
    Excess: 0
  };
  const bonus = {
    Excess: 3,
    Upper: 4,
    Numbers: 5,
    Symbols: 5,
    Combo: 0,
    FlatLower: 0,
    FlatUpper: 0,
    FlatNumber: 0
  };
  if (length) {
    for (let i = 0; i < length; i += 1) {
      const character = value.charAt(i);
      if (character.match(/[A-Z]/g)) {
        num.Upper += 1;
      }
      if (character.match(/[0-9]/g)) {
        num.Numbers += 1;
      }
      if (character.match(/(.*[!,@#$%^&*?_~])/)) {
        num.Symbols += 1;
      }
    }
  }
  let newComplexityPoints = 0;
  if (!length) newComplexityPoints = -1;
  else {
    if (length > 6) newComplexityPoints = 50;
    num.Excess = length - 6;
    if (num.Upper && num.Numbers && num.Symbols) {
      bonus.Combo = 25;
    } else if (
      (num.Upper && num.Numbers) ||
      (num.Upper && num.Symbols) ||
      (num.Numbers && num.Symbols)
    ) {
      bonus.Combo = 15;
    }
    if (value.match(/^[\sa-z]+$/)) {
      bonus.FlatLower = -15;
    }
    if (value.match(/^[\sA-Z]+$/)) {
      bonus.FlatUpper = -15;
    }
    if (value.match(/^[\s0-9]+$/)) {
      bonus.FlatNumber = -35;
    }
    newComplexityPoints +=
      num.Excess * bonus.Excess +
      num.Upper * bonus.Upper +
      num.Numbers * bonus.Numbers +
      num.Symbols * bonus.Symbols +
      bonus.Combo +
      bonus.FlatLower +
      bonus.FlatUpper +
      bonus.FlatNumber;
    if (newComplexityPoints < 50) newComplexityPoints = 50;
  }
  return newComplexityPoints;
};
