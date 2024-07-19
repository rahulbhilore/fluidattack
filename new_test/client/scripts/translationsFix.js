/**
 * Created by Dima Graebert on 1/14/2017.
 */
const fs = require("fs");

/* eslint-disable no-console */
fs.readFile("en.json", "utf8", (err, data) => {
  if (err) {
    console.error(err);
    return err;
  }
  console.log("The english translations file was read!");
  const translationsJSON = JSON.parse(data);
  let keys = Object.keys(translationsJSON);
  keys = keys.sort();

  let stringToWrite = "{\r\n";
  keys.map(key => {
    stringToWrite += `  "${keys[key].trim()}": "${translationsJSON[
      keys[key]
    ].trim()}",\r\n`;
    return key;
  });
  stringToWrite = stringToWrite.substr(0, stringToWrite.length - 3);
  stringToWrite += "\r\n}";
  const notTranslatedStrings = {};
  fs.writeFile("en.json", stringToWrite, writeError => {
    if (writeError) {
      return console.log(writeError);
    }
    console.log("The english translations file was saved!");
    fs.readdir(".", (readErr, files) => {
      const translationFilesToCheck = files.filter(
        fileName =>
          fileName.indexOf(".json") > -1 &&
          fileName.indexOf("untranslated") === -1 &&
          fileName !== "en.json"
      );
      translationFilesToCheck.forEach((fileName, index) => {
        console.log("Trying to parse translations file:", fileName);
        fs.readFile(fileName, "utf8", (translationErr, translationData) => {
          if (translationErr) {
            return console.log(translationErr);
          }
          const currentTranslationsJSON = JSON.parse(translationData);

          let translationString = "{\r\n";

          const languageCode = fileName.substr(0, fileName.indexOf(".json"));
          keys.map(key => {
            if (!currentTranslationsJSON[keys[key]]) {
              if (notTranslatedStrings[keys[key]]) {
                notTranslatedStrings[keys[key]][languageCode] = null;
              } else {
                notTranslatedStrings[keys[key]] = {
                  [languageCode]: null,
                  en: translationsJSON[keys[key]]
                };
              }
            } else if (
              currentTranslationsJSON[keys[key]] === translationsJSON[keys[key]]
            ) {
              if (notTranslatedStrings[keys[key]]) {
                notTranslatedStrings[keys[key]][languageCode] =
                  currentTranslationsJSON[keys[key]];
              } else {
                notTranslatedStrings[keys[key]] = {
                  [languageCode]: currentTranslationsJSON[keys[key]],
                  en: translationsJSON[keys[key]]
                };
              }
            }
            translationString += `  "${keys[key].trim()}": "${(
              currentTranslationsJSON[keys[key]] ||
              translationsJSON[keys[key]] ||
              ""
            ).trim()}",\r\n`;
            return key;
          });
          if (index === translationFilesToCheck.length - 1) {
            console.log(
              "Total number of strings that aren't translated to all available languages:",
              Object.keys(notTranslatedStrings).length || 0
            );
            fs.writeFile(
              "untranslated.json",
              JSON.stringify(notTranslatedStrings, null, 2),
              untranslatedErr => {
                if (untranslatedErr) {
                  return console.log(untranslatedErr);
                }
                console.log("Untraslated.json file saved!");
                return true;
              }
            );
          }
          translationString = translationString.substr(
            0,
            translationString.length - 3
          );
          translationString += "\r\n}";
          fs.writeFile(fileName, translationString, saveErr => {
            if (saveErr) {
              return console.log(saveErr);
            }
            console.log("Translations file", fileName, " was saved!");
            return true;
          });
          return true;
        });
      });
    });
    return true;
  });
  return true;
});
