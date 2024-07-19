/* eslint-disable react/prop-types */
import { makeStyles } from "@material-ui/core";
import clsx from "clsx";
import React, { useEffect, useRef, useState } from "react";
import { MentionsInput, Mention } from "react-mentions";
import { GET } from "../../../../constants/appConstants/RequestsMethods";
import FilesListStore from "../../../../stores/FilesListStore";
import Requests from "../../../../utils/Requests";

const displayFunc = (id, display) => {
  const formattedDisplay = display.substring(0, display.indexOf("<") - 1);
  return `@${formattedDisplay}`;
};

const formatData = users =>
  users.map(user => ({
    id: user._id,
    display: `${user.username} <${user.email}>`,
    ...user
  }));

const useStyles = makeStyles(theme => ({
  portal: {
    zIndex: 1
  },
  root: {
    minHeight: "72px",
    "&__control": {
      minHeight: "72px",
      backgroundColor: theme.palette.LIGHT
    },
    "&__input": {
      // lineHeight: "34px",
      color: theme.palette.JANGO,
      paddingLeft: theme.spacing(1),
      paddingRight: theme.spacing(1),
      backgroundColor: theme.palette.DARK
    }
  },
  mention: {
    padding: theme.spacing(1),
    fontSize: 12,
    color: theme.palette.LIGHT,
    backgroundColor: theme.palette.DARK,
    transitionDuration: ".2s"
  },
  focusedMention: {
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT
  }
}));

export default function CommentInput({
  className,
  onTextChange,
  text,
  isControlled
}) {
  const portal = useRef(null);
  const [value, setValue] = useState(text);
  const [pattern, setPattern] = useState(null);
  const [dummyCache, setCache] = useState([]);
  const onChange = e => {
    if (!isControlled) {
      setValue(e.target.value);
    }
    if (onTextChange) {
      onTextChange(e.target.value);
    }
  };
  useEffect(() => {
    if (isControlled) {
      setValue(text);
    }
  }, [text, isControlled]);

  const findUser = (str, callback) => {
    if (str !== pattern) {
      setPattern(str);
      const headers = Requests.getDefaultUserHeaders();
      headers.pattern = str;
      headers.fileId = FilesListStore.getCurrentFile()._id;
      Requests.sendGenericRequest(`/users/mention`, GET, headers).then(
        response => {
          const endData = formatData(response.data.results);
          setCache(endData);
          callback(endData);
        }
      );
    } else {
      callback(dummyCache);
    }
  };
  const clearPattern = () => {
    setPattern(null);
  };
  const classes = useStyles();
  const renderSuggestion = (
    suggestion,
    search,
    highlightedDisplay,
    index,
    focused
  ) => (
    <div
      className={clsx(classes.mention, {
        [classes.focusedMention]: focused
      })}
    >
      {highlightedDisplay}
    </div>
  );

  return (
    <>
      <div ref={portal} className={classes.portal} />
      <MentionsInput
        value={value}
        onChange={onChange}
        allowSpaceInQuery
        className={clsx(classes.root, className)}
        allowSuggestionsAboveCursor
        suggestionsPortalHost={portal.current}
      >
        <Mention
          trigger="@"
          markup="[__display__| __id__ ]"
          data={findUser}
          onAdd={clearPattern}
          displayTransform={displayFunc}
          renderSuggestion={renderSuggestion}
        />
      </MentionsInput>
    </>
  );
}
