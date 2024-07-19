type Language = {
  adminOnly: boolean;
  country: string;
  lang: string;
  locale: string;
  name: string;
};

interface Window {
  _trackJs?:
    | {
        token: string;
        version: string;
        enabled: boolean;
        application: string;
      }
    | undefined;
  ARESKudoConfigObject: {
    languages: Language[];
    customerPortalURL: string;
    defaultTitle: string;
  };

  _globalXeTestLogs: Array<object>;
  _downloadXeTestLogs: () => void;
  performanceTest: {
    start: (
      testSet: string,
      stuckTime?: number | null,
      loops?: number
    ) => boolean;
    halt: () => void;
    move: (stuckTime?: number) => void;
    regen: (stuckTime?: number) => void;
    zoom: (stuckTime?: number) => void;
  };
}
