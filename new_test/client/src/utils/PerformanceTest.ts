import _ from "underscore";
import FilesListStore from "../stores/FilesListStore";
import XenonConnectionActions from "../actions/XenonConnectionActions";
import XenonConnectionStore from "../stores/XenonConnectionStore";
import applicationStore from "../stores/ApplicationStore";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import Requests from "./Requests";
import MainFunctions from "../libraries/MainFunctions";
import Storage from "./Storage";
import AccessToken, {
  IncomingAccessTokenParameters
} from "../components/Pages/DrawingLoader/AccessToken";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";
import { FileObject } from "./FileSort";
import { defaultLocale } from "./languages";

const FILE_TYPE = "file";

const TIME_TEST_STUCK = 90; // seconds
const MIN_STUCK_TIME = 10;
const REPEATS = 1; // how many times repeat
type TestResult = {
  fileName: string;
  fileId: string;
  testSet: string;
  clientTime: number | string;
  serverTime: number | string;
  // we don't really know the result
  result: object;
};

type XenonMetadata = {
  sessionId: string;
  revision: string;
  stream: string;
};

type XeTestMessage = {
  name: "xetest";
  clientTime: number;
  serverTime: number;
  testData: object;
  appdata?: {
    session?: {
      sessionId: string;
    };
    version?: {
      product: string;
      stream: string;
    };
  };
};

/**
 * This class needed to run XENON performance test in current directory
 * @class PerformanceTest
 */
export default class PerformanceTest {
  private _testFrame: HTMLIFrameElement | null;

  private _filesList: Array<FileObject> | null;

  private _currentTestSet: string | null;

  private _timerId: NodeJS.Timeout | null;

  private _testData: Array<TestResult>;

  private _startTime: number | null;

  private _xeAppData: XenonMetadata | null;

  private repeats: number;

  private testTimeout: number;

  private baseParameters: IncomingAccessTokenParameters;

  constructor() {
    this._testFrame = null;
    this._filesList = null;
    this._currentTestSet = null;
    this._timerId = null;
    this._testData = [];
    this._startTime = null;
    this._xeAppData = null;
    this.repeats = REPEATS;
    this.testTimeout = TIME_TEST_STUCK;
    this.baseParameters = {
      server: applicationStore.getApplicationSetting("server"),
      isViewOnly: false,
      sessionId: Storage.getItem("sessionId") || "",
      locale: Storage.getItem("locale") || defaultLocale,
      ribbonMode: false
    };
  }

  /**
   * Start tests on all files in current directory
   * @param testSet name of test set
   * @param stuckTime time after which the test is considered failed
   * @returns {boolean|void}
   */
  start = (
    testSet: string,
    stuckTime: number | null = null,
    loops = 1
  ): boolean => {
    this.baseParameters = {
      server: applicationStore.getApplicationSetting("server"),
      isViewOnly: false,
      sessionId: Storage.getItem("sessionId") || "",
      locale: Storage.getItem("locale") || defaultLocale,
      ribbonMode: false
    };

    this._startTime = Date.now();
    const { _id } = FilesListStore.getCurrentFolder();

    this._filesList = FilesListStore.getTreeData(_id).filter(
      (elem: FileObject) => elem.type === FILE_TYPE
    );

    if (stuckTime && _.isNumber(stuckTime) && stuckTime >= MIN_STUCK_TIME)
      this.testTimeout = stuckTime;

    if (loops && _.isNumber(loops)) {
      this.repeats = loops;
    }

    if (!this._filesList) return false;
    this._attachListeners();

    SnackbarUtils.testsInProgress();
    this._currentTestSet = testSet || "REGEN";

    this.repeats -= 1;
    this._runTest(this._filesList.shift(), this._currentTestSet);
    return true;
  };

  /**
   * Start zoom tests on all files in current directory
   * @returns {void}
   */
  zoom = (stuckTime: number | null = null): void => {
    this.start("ZOOM", stuckTime);
  };

  /**
   * Start regen tests on all files in current directory
   * @returns {void}
   */
  regen = (stuckTime: number | null = null): void => {
    this.start("REGEN", stuckTime);
  };

  /**
   * Start move tests on all files in current directory
   * @returns {void}
   */
  move = (stuckTime: number | null = null): void => {
    this.start("MOVE", stuckTime);
  };

  /**
   * Immediately stops tests
   */
  halt = () => {
    this._removeListeners();
    this._generateResult();
    this._testFrame?.remove();
    SnackbarUtils.stopTestsInProgress();
  };

  /**
   * @private
   */
  _attachListeners() {
    XenonConnectionActions.connect();
    XenonConnectionStore.addChangeListener(this._messageHandler);
  }

  /**
   * @private
   */
  _removeListeners() {
    XenonConnectionStore.removeChangeListener(this._messageHandler);
    XenonConnectionActions.disconnect();
    if (this._timerId) clearTimeout(this._timerId);
  }

  /**
   * @private
   */
  _messageHandler = () => {
    const { lastMessage } = XenonConnectionStore.getCurrentState();
    if (!lastMessage) return;
    const formattedXeMessage = lastMessage as XeTestMessage;
    if (formattedXeMessage.name !== "xetest") return;

    if (this._timerId) clearTimeout(this._timerId);

    const currentFile = FilesListStore.getCurrentFile();

    const result: TestResult = {
      fileName: currentFile.name,
      fileId: currentFile.id,
      testSet: this._currentTestSet || "",
      clientTime: formattedXeMessage.clientTime,
      serverTime: formattedXeMessage.serverTime,
      result: formattedXeMessage.testData
    };
    if (formattedXeMessage.appdata) {
      this._xeAppData = {
        sessionId: formattedXeMessage.appdata?.session?.sessionId || "",
        revision: formattedXeMessage.appdata?.version?.product || "",
        stream: formattedXeMessage.appdata?.version?.stream || ""
      };
    }

    this._testData.push(result);
    this._onTestComplete();
  };

  /**
   * @private
   */
  _failureHandler = () => {
    const currentFile = FilesListStore.getCurrentFile();

    const result: TestResult = {
      fileName: currentFile.name,
      fileId: currentFile.id,
      testSet: this._currentTestSet || "",
      clientTime: "FAILURE",
      serverTime: "NO_POSTMESSAGE_RECEIVED",
      result: {}
    };

    this._testData.push(result);
    this._onTestComplete();
  };

  /**
   * @private
   */
  _onTestComplete = () => {
    this._testFrame?.remove();

    if (!this._filesList?.length) {
      this._removeListeners();
      SnackbarUtils.stopTestsInProgress();
      if (this.repeats > 0) {
        setTimeout(() => {
          this.start(
            this._currentTestSet || "",
            this.testTimeout,
            this.repeats
          );
        }, 500);
      } else {
        this._generateResult();
        this._testData = [];
      }
      return;
    }

    this._runTest(this._filesList.shift(), this._currentTestSet || "");
  };

  /**
   * @param fileInfo
   * @param testSet
   * @private
   */
  _runTest(fileInfo?: FileObject, testSet?: string) {
    if (!fileInfo || !testSet) return;
    FilesListStore.saveCurrentFile(fileInfo);
    AccessToken.create(fileInfo.id, {
      ...this.baseParameters,
      testset: testSet
    }).then(link => {
      this._testFrame = document.createElement("iframe");
      document.getElementsByTagName("body")[0].appendChild(this._testFrame);

      this._testFrame.setAttribute(
        "style",
        "left: 0; opacity: 1; z-index: 1040;"
      );
      this._testFrame.src = link;
      this._testFrame.id = "draw";
      this._testFrame.title = "editor";

      this._timerId = setTimeout(this._failureHandler, this.testTimeout * 1000);
    });
  }

  /**
   * @private
   */
  _generateResult() {
    const rows = [
      [
        "File name",
        "File Id",
        "Test set",
        "Client Time",
        "Server Time",
        "App data",
        "Test results"
      ]
    ];

    const csvContent = `data:text/csv;charset=utf-8, ${rows
      .concat(
        this._testData.map(el =>
          Object.values(el)
            .map(e => JSON.stringify(e))
            .join(",")
        )
      )
      .join("\n")}`;

    const encodedUri = encodeURI(csvContent);

    const link = document.createElement("a");
    link.setAttribute("style", "display: none");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", "tests_results.csv");
    document.body.appendChild(link);
    link.click();
    link.remove();
    this._getJsonData();
  }

  _getJsonData() {
    const { _id, storage } = FilesListStore.getCurrentFolder();
    const metadata = {
      fluorine: {
        revision: applicationStore.getApplicationSetting("revision"),
        url: location.host,
        folderId: _id,
        storage: MainFunctions.storageCodeToServiceName(storage)
      },
      timings: {
        start: this._startTime,
        end: Date.now()
      },
      xenon: {
        ...this._xeAppData,
        // this isn't fully correct as we don't use apitoken here, but probably we don't have to
        url:
          Storage.getItem("customEditorURL") ||
          applicationStore.getApplicationSetting("editorURL")
      },
      tests: this._testData
    };
    const csvContent = `data:text/json;charset=utf-8, ${JSON.stringify(
      metadata,
      null,
      2
    )}`;

    const encodedUri = encodeURI(csvContent);

    const link = document.createElement("a");
    link.setAttribute("style", "display: none");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", "results.json");
    document.body.appendChild(link);
    link.click();
    link.remove();
  }

  _saveToServer() {
    const { _id, storage } = FilesListStore.getCurrentFolder();
    const metadata = {
      fluorine: {
        revision: applicationStore.getApplicationSetting("revision"),
        url: location.host,
        folderId: _id,
        storage: MainFunctions.storageCodeToServiceName(storage)
      },
      xenon: {
        // this isn't fully correct as we don't use apitoken here, but probably we don't have to
        url:
          Storage.getItem("customEditorURL") ||
          applicationStore.getApplicationSetting("editorURL")
      },
      tests: this._testData
    };
    Requests.sendGenericRequest(
      `/stats/performance`,
      RequestsMethods.POST,
      Requests.getDefaultUserHeaders(),
      metadata
    ).then(() => {
      // eslint-disable-next-line no-console
      console.info("Data sent to server");
    });
  }
}

window.performanceTest = new PerformanceTest();
