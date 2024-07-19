import React, { Component } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import Breadcrumbs from "@material-ui/core/Breadcrumbs";
import Link from "@material-ui/core/Link";
import Tooltip from "@material-ui/core/Tooltip";
import { Link as RLink } from "react-router";
import withStyles from "@material-ui/core/styles/withStyles";
import { Typography } from "@material-ui/core";
import Separator from "../../Breadcrumbs/Separator";
import HomeIcon from "../../Breadcrumbs/HomeIcon";
import MainFunctions from "../../../libraries/MainFunctions";

const betweenNumConstant = 5;

const BreadcrumbsContainer = withStyles(theme => ({
  root: {
    marginLeft: 30,
    marginTop: theme.spacing(2),
    marginBottom: theme.spacing(2),
    "&.loading": {
      opacity: 0.3
    },
    "&.search": {
      margin: 0,
      backgroundColor: theme.palette.YELLOW_BREADCRUMB,
      padding: "8px 0 8px 5px"
    },
    "@media (max-width: 767px)": {
      marginTop: theme.spacing(1),
      marginBottom: theme.spacing(1),
      marginLeft: theme.spacing(1)
    }
  },
  separator: {
    margin: 0
  }
}))(Breadcrumbs);

// Breadcrumbs should be either controlled or uncontrolled
// Controlled - pass path
// Uncontrolled - pass targetId

export default class BlocksBreadcrumbs extends Component {
  static propTypes = {
    path: PropTypes.arrayOf(
      PropTypes.shape({
        name: PropTypes.string.isRequired,
        link: PropTypes.string.isRequired
      })
    )
  };

  static defaultProps = {
    path: []
  };

  render() {
    const { path } = this.props;
    if (path.length === 0) return 0;
    const last = _.last(path);
    const first = _.first(path);

    const lastTruncatedName = MainFunctions.shrinkString(last.name);
    const lastTooltipRequired = lastTruncatedName.length < last.name.length;
    return (
      <BreadcrumbsContainer
        separator={<Separator />}
        aria-label="breadcrumb"
        maxItems={betweenNumConstant}
        itemsAfterCollapse={2}
      >
        <RLink to={first.link}>
          <Link href={first.link}>
            <HomeIcon isTrash={false} />
          </Link>
        </RLink>
        {path.slice(1, path.length - 1).map(element => {
          const truncatedName = MainFunctions.shrinkString(element.name);
          if (truncatedName.length < element.name.length) {
            return (
              <Tooltip placement="top" title={element.name}>
                <RLink to={first.link}>
                  <Link href={element.link}>{truncatedName}</Link>
                </RLink>
              </Tooltip>
            );
          }
          return (
            <RLink to={first.link}>
              <Link href={element.link}>{truncatedName}</Link>
            </RLink>
          );
        })}
        {path.length > 1 && lastTooltipRequired ? (
          <Tooltip placement="top" title={last.name}>
            <Typography style={{ color: "black" }}>
              {lastTruncatedName}
            </Typography>
          </Tooltip>
        ) : null}
        {path.length > 1 && !lastTooltipRequired ? (
          <Typography style={{ color: "black" }}>{last.name}</Typography>
        ) : null}
      </BreadcrumbsContainer>
    );
  }
}
