import _ from "underscore";
import mat4 from "../libraries/gl-matrix-0.9.5";
import { isWebGLSupported } from "../libraries/CompabilityCheck";

const EXECUTION_LIMIT = 150;
const AMOUNT_OF_TESTS = 100;

export type WebGLTestState = {
  isWebGLSupported: boolean;
  isTestStarted: boolean;
  isTestPassed: boolean;
  avgTime: number;
  fullTime: number;
  description: string | null;
  stack?: Array<string>;
  testsNumber?: number;
  maxAllowedTestTime?: number;
  times?: Array<number>;
};

type ItemAndNum = {
  itemSize: number;
  numItems: number;
};

export default class WebGLTest {
  static fragmentShader =
    "precision mediump float;void main(void) {gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);}";

  static vertexShader =
    "attribute vec3 aVertexPosition;uniform mat4 uMVMatrix;uniform mat4 uPMatrix;void main(void) {gl_Position = uPMatrix * uMVMatrix * vec4(aVertexPosition, 1.0);}";

  state: WebGLTestState;

  gl: WebGL2RenderingContext | null;

  shaderProgram: WebGLProgram | null;

  triangleVertexPositionBuffer: WebGLBuffer | null;

  squareVertexPositionBuffer: WebGLBuffer | null;

  mvMatrix: ReturnType<typeof mat4.create>;

  pMatrix: ReturnType<typeof mat4.create>;

  canvas: HTMLCanvasElement | null = null;

  vertexPositionAttribute: number;

  pMatrixUniform: WebGLUniformLocation | null;

  mvMatrixUniform: WebGLUniformLocation | null;

  triangleBufferItems: ItemAndNum;

  squareBufferItems: ItemAndNum;

  constructor() {
    this.state = {
      isWebGLSupported: isWebGLSupported(true).valid,
      isTestStarted: false,
      isTestPassed: false,
      avgTime: 0,
      fullTime: 0,
      description: null
    };
    this.gl = null;
    this.shaderProgram = null;
    this.triangleVertexPositionBuffer = null;
    this.squareVertexPositionBuffer = null;
    this.mvMatrix = null;
    this.pMatrix = null;
  }

  handleError(description: string, stack?: Array<string>) {
    this.state = {
      ...this.state,
      isTestStarted: false,
      isTestPassed: false,
      avgTime: -1,
      description,
      stack
    };
    this.sendData();
  }

  initGL(canvas: HTMLCanvasElement) {
    try {
      this.gl = canvas.getContext("webgl2");
      if (this.gl) {
        this.gl.viewport(0, 0, canvas.width, canvas.height);
      }
      this.canvas = canvas;
    } catch (e) {
      this.handleError(e.message);
    }
    if (!this.gl) {
      this.handleError("Cannot initialize WebGL");
    }
  }

  getShader(text: string, shaderType: number): null | WebGLShader {
    if (!this.gl) return null;
    let shader = null;
    if (shaderType === this.gl.FRAGMENT_SHADER) {
      shader = this.gl.createShader(this.gl.FRAGMENT_SHADER);
    } else if (shaderType === this.gl.VERTEX_SHADER) {
      shader = this.gl.createShader(this.gl.VERTEX_SHADER);
    }
    if (!shader) return null;

    this.gl.shaderSource(shader, text);
    this.gl.compileShader(shader);

    if (!this.gl.getShaderParameter(shader, this.gl.COMPILE_STATUS)) {
      // eslint-disable-next-line no-console
      console.info(this.gl.getShaderInfoLog(shader));
      return null;
    }

    return shader;
  }

  initShaders() {
    if (!this.gl) return;
    const loadedFragmentShader = this.getShader(
      WebGLTest.fragmentShader,
      this.gl.FRAGMENT_SHADER
    );
    const loadedVertexShader = this.getShader(
      WebGLTest.vertexShader,
      this.gl.VERTEX_SHADER
    );

    if (!loadedFragmentShader || !loadedVertexShader) {
      this.handleError("Could not initialise shaders");
    }

    this.shaderProgram = this.gl.createProgram();

    if (!this.shaderProgram) {
      this.handleError("Could not initialise shaders");
      return;
    }

    this.gl.attachShader(this.shaderProgram, loadedVertexShader as WebGLShader);
    this.gl.attachShader(
      this.shaderProgram,
      loadedFragmentShader as WebGLShader
    );
    this.gl.linkProgram(this.shaderProgram);

    if (!this.gl.getProgramParameter(this.shaderProgram, this.gl.LINK_STATUS)) {
      this.handleError("Could not initialise shaders");
    }

    this.gl.useProgram(this.shaderProgram);

    this.vertexPositionAttribute = this.gl.getAttribLocation(
      this.shaderProgram,
      "aVertexPosition"
    );
    this.gl.enableVertexAttribArray(this.vertexPositionAttribute);

    this.pMatrixUniform = this.gl.getUniformLocation(
      this.shaderProgram,
      "uPMatrix"
    );
    this.mvMatrixUniform = this.gl.getUniformLocation(
      this.shaderProgram,
      "uMVMatrix"
    );
  }

  setMatrixUniforms() {
    if (!this.gl || !this.shaderProgram) return;
    this.gl.uniformMatrix4fv(this.pMatrixUniform, false, this.pMatrix);
    this.gl.uniformMatrix4fv(this.mvMatrixUniform, false, this.mvMatrix);
  }

  initBuffers() {
    if (!this.gl) return;
    this.triangleVertexPositionBuffer = this.gl.createBuffer();
    if (!this.triangleVertexPositionBuffer) {
      this.handleError("Could not initialise buffers");
      return;
    }
    this.gl.bindBuffer(this.gl.ARRAY_BUFFER, this.triangleVertexPositionBuffer);
    let vertices = [0.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0, -1.0, 0.0];
    this.gl.bufferData(
      this.gl.ARRAY_BUFFER,
      new Float32Array(vertices),
      this.gl.STATIC_DRAW
    );
    this.triangleBufferItems = {
      itemSize: 3,
      numItems: 3
    };

    this.squareVertexPositionBuffer = this.gl.createBuffer();
    if (!this.squareVertexPositionBuffer) {
      this.handleError("Could not initialize buffers");
      return;
    }
    this.gl.bindBuffer(this.gl.ARRAY_BUFFER, this.squareVertexPositionBuffer);
    vertices = [1.0, 1.0, 0.0, -1.0, 1.0, 0.0, 1.0, -1.0, 0.0, -1.0, -1.0, 0.0];
    this.gl.bufferData(
      this.gl.ARRAY_BUFFER,
      new Float32Array(vertices),
      this.gl.STATIC_DRAW
    );

    this.squareBufferItems = {
      itemSize: 3,
      numItems: 4
    };
  }

  drawScene() {
    if (!this.gl || !this.canvas) return;
    this.gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    // eslint-disable-next-line no-bitwise
    this.gl.clear(this.gl.COLOR_BUFFER_BIT | this.gl.DEPTH_BUFFER_BIT);

    mat4.perspective(
      45,
      this.canvas.width / this.canvas.height,
      0.1,
      100.0,
      this.pMatrix
    );

    mat4.identity(this.mvMatrix);

    mat4.translate(this.mvMatrix, [-1.5, 0.0, -7.0]);
    this.gl.bindBuffer(this.gl.ARRAY_BUFFER, this.triangleVertexPositionBuffer);
    this.gl.vertexAttribPointer(
      this.vertexPositionAttribute,
      this.triangleBufferItems.itemSize,
      this.gl.FLOAT,
      false,
      0,
      0
    );
    this.setMatrixUniforms();
    this.gl.drawArrays(this.gl.TRIANGLES, 0, this.triangleBufferItems.numItems);

    mat4.translate(this.mvMatrix, [3.0, 0.0, 0.0]);
    this.gl.bindBuffer(this.gl.ARRAY_BUFFER, this.squareVertexPositionBuffer);
    this.gl.vertexAttribPointer(
      this.vertexPositionAttribute,
      this.squareBufferItems.itemSize,
      this.gl.FLOAT,
      false,
      0,
      0
    );
    this.setMatrixUniforms();
    this.gl.drawArrays(
      this.gl.TRIANGLE_STRIP,
      0,
      this.squareBufferItems.numItems
    );
  }

  runTest() {
    try {
      this.state.isTestStarted = true;
      this.mvMatrix = mat4.create();
      this.pMatrix = mat4.create();

      const canvas = document.createElement("canvas");
      canvas.width = 2000;
      canvas.height = 2000;
      canvas.style.display = "none";
      document.body.appendChild(canvas);

      this.initGL(canvas);
      // on errors isTestStarted set to false, so check to stop if errors occur
      if (this.state.isTestStarted === true) {
        this.initShaders();
      }
      if (this.state.isTestStarted === true) {
        this.initBuffers();
      }

      if (!this.gl) {
        this.handleError("Cannot initialize WebGL");
        return;
      }

      this.gl.clearColor(0.0, 0.0, 0.0, 1.0);
      this.gl.enable(this.gl.DEPTH_TEST);
      if (this.state.isTestStarted === true) {
        this.drawScene();
      }

      let sumTime = 0;
      this.state.testsNumber = AMOUNT_OF_TESTS;
      const maxAllowedTestTime = AMOUNT_OF_TESTS * EXECUTION_LIMIT;
      this.state.maxAllowedTestTime = maxAllowedTestTime;
      const pixels = new Uint8Array(4 * 2000 * 2000);

      const globalTestStartTime = Date.now();

      this.state.times = [];
      for (let i = 0; i < AMOUNT_OF_TESTS; i += 1) {
        const startTime = Date.now();
        this.gl.readPixels(
          0,
          0,
          2000,
          2000,
          this.gl.RGBA,
          this.gl.UNSIGNED_BYTE,
          pixels
        );
        const endTime = Date.now();
        this.state.times.push(endTime - startTime);
        sumTime += endTime - startTime;

        if (endTime - globalTestStartTime > maxAllowedTestTime) {
          this.state.description = "Timeout";
          this.state.isTestPassed = false;
          this.state.fullTime = endTime - globalTestStartTime;
          this.state.avgTime = this.state.fullTime / i;

          break;
        }
      }

      this.state.description = "Finished";
      this.state.fullTime = sumTime;
      this.state.avgTime = sumTime / AMOUNT_OF_TESTS;
      this.state.isTestPassed = this.state.avgTime <= EXECUTION_LIMIT;

      if (this.gl.getExtension("WEBGL_lose_context")) {
        this.gl.getExtension("WEBGL_lose_context")?.loseContext();
      }

      this.gl = null;
      this.shaderProgram = null;
      this.triangleVertexPositionBuffer = null;
      this.squareVertexPositionBuffer = null;
      this.mvMatrix = null;
      this.pMatrix = null;

      document.body.removeChild(canvas);
    } catch (e) {
      this.handleError(e.message);
    }

    this.sendData();
  }

  sendData() {
    const data = {
      messageName: "glTestResults",
      data: this.state
    };

    if (window.parent && window.parent.postMessage) {
      window.parent.postMessage(data, "*");
    }
  }
}
