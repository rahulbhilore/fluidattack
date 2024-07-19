/* eslint linebreak-style:0 no-console:0 */
import fs from "fs";
import path from "path";
import { DateTime } from "luxon";
// eslint-disable-next-line import/no-extraneous-dependencies
import chalk from "chalk";
import paths from "../config/paths.js";

function log() {
  const timestampFormat = chalk.bold.yellow;
  /* eslint-disable */
  const logBaseArguments = [].slice.call(arguments).join(" ");
  /* eslint-enable */
  const currentLogStamps = [
    "[",
    timestampFormat(DateTime.now().toFormat("HH:mm:ss.SSS DD.MM")),
    "]"
  ];
  console.log.apply(this, currentLogStamps.concat(logBaseArguments));
}

function copyFile(source, target, cb) {
  let cbCalled = false;

  function done(err) {
    if (!cbCalled) {
      cb(err);
      cbCalled = true;
    }
  }
  const rd = fs.createReadStream(source);
  rd.on("error", err => done(err));
  const wr = fs.createWriteStream(target);
  wr.on("error", err => done(err));
  wr.on("close", () => done());
  rd.pipe(wr);
}

const moveTemplateFile = (templatesPath, fileName) =>
  new Promise(resolve => {
    const fullFilePath = path.join(templatesPath, fileName);
    const newFilePath = path.resolve(`${paths.appPublic}/${fileName}`);
    log(`Copying template from ${fullFilePath} to ${newFilePath}`);
    copyFile(fullFilePath, newFilePath, err => {
      if (err) {
        throw new Error(err);
      }
      resolve();
    });
  });

const moveConfig = (product, appPath, publicPath) =>
  new Promise(resolve => {
    const productConfig = path.resolve(
      appPath,
      publicPath,
      `./configs/${product}.json`
    );
    const targetConfig = path.resolve(
      appPath,
      publicPath,
      "./configs/config.json"
    );
    log(`Moving config file from ${productConfig} to ${targetConfig}`);
    copyFile(productConfig, targetConfig, resolve);
  });

const loadHTMLTemplates = product =>
  new Promise(resolve => {
    // check for custom template
    const templatesPath = path.resolve(
      `${paths.appTemplates}/${product}/HTML/`
    );
    log(`{ ${product} } Checking HTML templates in folder ${templatesPath}`);
    fs.readdir(templatesPath, (err, files) => {
      // handling error
      if (err) {
        log(`{ ${product} } Unable to scan directory: ${err}`);
        process.exit(5);
      }
      // listing all files using forEach
      const templatesProccessing = files.map(file =>
        moveTemplateFile(templatesPath, file)
      );

      Promise.all(templatesProccessing).then(() => {
        log(`{ ${product} } All templates were processed`);
        resolve();
      });
    });
  });

const getTargetProduct = appArg => {
  const app = (appArg || "kudo").trim().toLowerCase();
  switch (app) {
    case "ak":
    case "kudo":
      return "kudo";
    case "ds":
    case "sw":
    case "draftsight":
      return "ds";
    default:
      log("Incorrect app name.");
      return null;
  }
};

const productCodeToName = productCode => {
  if (productCode === "ds") return "DraftSight";
  return "ARES Kudo";
};

const updateAPIURL = ({
  appPath,
  publicPath,
  product,
  instanceType,
  releaseNumber,
  isProd = true,
  port
}) =>
  new Promise(resolve => {
    const targetConfigPath = path.resolve(
      appPath,
      publicPath,
      "./configs/config.json"
    );

    // load config
    const config = JSON.parse(fs.readFileSync(targetConfigPath).toString());
    let newURL = "https://fluorine-master-prod-latest-ue1.dev.graebert.com/api";
    switch (instanceType) {
      case "local":
        newURL = `http://localhost:${port || 8080}`;
        break;
      case "release":
        if (product === "ds") {
          newURL = `https://fluorine-rel-1-${
            releaseNumber || 150
          }-sw-latest-ue1.app.draftsight.com/api`;
        } else {
          newURL = `https://fluorine-rel-1-${releaseNumber || 150}-${
            isProd ? "prod-" : ""
          }latest-ue1.dev.graebert.com/api`;
        }
        break;
      case "staging":
        if (product === "ds") {
          newURL = "https://staging.app.draftsight.com/api";
        } else {
          newURL = "https://staging.kudo.graebert.com/api";
        }
        break;
      case "production":
        if (product === "ds") {
          newURL = "https://app.draftsight.com/api";
        } else {
          newURL = "https://kudo.graebert.com/";
        }
        break;
      case "master":
      default:
        if (product === "ds") {
          newURL =
            "https://fluorine-master-sw-latest-ue1.app.draftsight.com/api";
        } else {
          newURL = `https://fluorine-master-${
            isProd ? "prod-" : ""
          }latest-ue1.dev.graebert.com/api`;
        }
        break;
    }
    config.api = newURL;
    fs.writeFile(targetConfigPath, JSON.stringify(config, null, "\t"), err => {
      if (err) {
        console.log(err);
      }
      log(`API URL has been updated`);
      resolve();
    });
  });

const getAllTranslationFiles = appPath =>
  new Promise(resolve => {
    fs.readdir(
      path.resolve(appPath, "src", "assets", "translations"),
      (err, list) => {
        resolve(list.filter(v => v.endsWith(".json")));
      }
    );
  });

const getTranslationFromFile = (appPath, translationFileName, translationKey) =>
  new Promise(resolve => {
    fs.readFile(
      path.resolve(
        appPath,
        "src",
        "assets",
        "translations",
        translationFileName
      ),
      (err, data) => {
        try {
          const json = JSON.parse(data.toString());
          if (Object.prototype.hasOwnProperty.call(json, translationKey)) {
            resolve({ [translationFileName]: json[translationKey] });
          } else {
            resolve("");
          }
        } catch (ex) {
          console.error(
            `Error getting translation ${translationKey} from file ${translationFileName}: ${ex.toString()}`
          );
          resolve("");
        }
      }
    );
  });

const prepareInitialTranslations = async (appPath, publicPath, product) => {
  const listOfTranslationFiles = await getAllTranslationFiles(appPath);
  const map = {};
  const prettyProductName = productCodeToName(product);
  const translations = await Promise.all(
    listOfTranslationFiles.map(f =>
      getTranslationFromFile(appPath, f, "productIsLoadingNow")
    )
  );
  translations.forEach(translationMap => {
    const [fileKey] = Object.keys(translationMap);
    const [translationString] = Object.values(translationMap);
    map[fileKey.substring(0, fileKey.indexOf(".json"))] =
      translationString.replace("{product}", prettyProductName);
  });
  fs.writeFileSync(
    path.resolve(publicPath, "initial", "translations.json"),
    JSON.stringify(map)
  );
};
export {
  moveConfig,
  loadHTMLTemplates,
  getTargetProduct,
  updateAPIURL,
  prepareInitialTranslations
};
