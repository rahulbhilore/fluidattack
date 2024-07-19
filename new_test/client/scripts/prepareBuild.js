// KUDO_CHANGE: Kudo dependencies
// eslint-disable-next-line import/no-extraneous-dependencies
import minimist from "minimist";
import * as AKBuildFunctions from "./prepareAssets.js";
import paths from "../config/paths.js";
// Ensure environment variables are read.
import { loadEnv } from "../config/env.js";

const args = minimist(process.argv.slice(2));

// Do this as the first thing so that any code reading it knows the right env.
process.env.BABEL_ENV = args.dev ? "development" : "production";
process.env.NODE_ENV = args.dev ? "development" : "production";

if (args.test) {
  process.env.TEST_ENV = "jenkins";
  console.info("Setting TEST_ENV=jenkins");
}
loadEnv();

// Makes the script crash on unhandled rejections instead of silently
// ignoring them. In the future, promise rejections that are not handled will
// terminate the Node.js process with a non-zero exit code.
process.on("unhandledRejection", err => {
  throw err;
});

// KUDO_CHANGE: minimist will create an object which is easier to use
// Detect target product (kudo/ds/etc.)

const product = AKBuildFunctions.getTargetProduct(args.app);

// KUDO_CHANGE: Load templates and move config before CRA stuff
AKBuildFunctions.moveConfig(product, paths.appPath, paths.appPublic).then(
  () => {
    AKBuildFunctions.updateAPIURL({
      product,
      appPath: paths.appPath,
      instanceType: args.apitype,
      publicPath: paths.appPublic,
      releaseNumber: args.rel,
      isProd: args.prod,
      port: args.port
    }).then(() => {
      AKBuildFunctions.prepareInitialTranslations(
        paths.appPath,
        paths.appPublic,
        product
      ).then(() => {
        process.exit(0);
      });
    });
  }
);
