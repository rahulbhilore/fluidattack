/* eslint-disable import/no-extraneous-dependencies */
import { defineConfig, splitVendorChunkPlugin } from "vite";
import svgrPlugin from "vite-plugin-svgr";
import react from "@vitejs/plugin-react";
import eslint from "vite-plugin-eslint";
import legacy from "@vitejs/plugin-legacy";
import path from "path";
import gzipPlugin from "rollup-plugin-gzip";
import { brotliCompress } from "zlib";
import { promisify } from "util";
import { createHtmlPlugin } from "vite-plugin-html";
import { visualizer } from "rollup-plugin-visualizer";
import { Plugin, PluginBuild } from "vite/node_modules/esbuild/lib/main";

const brotliPromise = promisify(brotliCompress);

// react-virualized has an issue with this. See:
// https://github.com/bvaughn/react-virtualized/issues/1632
// https://github.com/bvaughn/react-virtualized/issues/1212
const resolveFixup: Plugin = {
  name: "resolve-fixup",
  setup(build: PluginBuild) {
    build.onResolve({ filter: /react-virtualized/ }, async () => ({
      path: path.resolve(
        "./node_modules/react-virtualized/dist/umd/react-virtualized.js"
      )
    }));
  }
};

// https://vitejs.dev/config/
export default defineConfig({
  build: {
    outDir: "build",
    sourcemap: "hidden"
  },
  esbuild: {
    legalComments: "none"
  },
  optimizeDeps: {
    esbuildOptions: {
      plugins: [resolveFixup]
    },
    needsInterop: ["react-virtualized"]
  },
  server: {
    port: 3000
  },
  plugins: [
    legacy(),
    splitVendorChunkPlugin(),
    createHtmlPlugin({
      minify: {
        minifyCSS: true,
        minifyJS: true,
        collapseInlineTagWhitespace: true,
        collapseWhitespace: true,
        html5: true,
        removeTagWhitespace: true
      },
      entry: "/src/src/generic.tsx"
    }),
    react(),
    eslint({ overrideConfigFile: path.resolve("./config/.eslintrc") }),
    svgrPlugin({
      svgrOptions: {
        icon: true
        // ...svgr options (https://react-svgr.com/docs/options/)
      }
    }),
    gzipPlugin(),
    gzipPlugin({
      customCompression: content => brotliPromise(Buffer.from(content)),
      fileName: ".br"
    }),
    visualizer()
  ]
});
