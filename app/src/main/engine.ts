import { ChildProcessWithoutNullStreams, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import readline from "node:readline";
import { app } from "electron";
import type { EngineError, EngineState, ProgressEvent } from "../shared/protocol";

interface Pending {
  resolve: (value: unknown) => void;
  reject: (error: EngineError) => void;
  progressKey?: string;
}

/**
 * The envx engine, running as `envx app-server` (JSON lines on stdin/stdout; ADR 0013). The installed app carries its
 * own Java runtime and the engine jars in its resources; a development run uses the engine built in ../engine.
 */
export class Engine {
  private proc?: ChildProcessWithoutNullStreams;
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private state: EngineState = { running: false };

  constructor(
    private readonly onProgress: (e: ProgressEvent) => void,
    private readonly onState: (s: EngineState) => void,
  ) {}

  getState(): EngineState {
    return this.state;
  }

  start(): void {
    const { java, classpath } = locate();
    if (java !== "java" && !existsSync(java)) {
      this.setState({ running: false, message: `The bundled Java runtime is missing (${java}). Reinstall envx.` });
      return;
    }
    this.proc = spawn(java, ["-Xss4m", "-XX:+UseSerialGC", "-cp", classpath, "dev.envx.cli.Main", "app-server"], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.setState({ running: true });
    readline.createInterface({ input: this.proc.stdout }).on("line", (line) => this.receive(line));
    const errors: string[] = [];
    this.proc.stderr.on("data", (d: Buffer) => {
      errors.push(d.toString());
      if (errors.length > 20) errors.shift();
    });
    this.proc.on("error", (e) => this.setState({ running: false, message: `The engine could not start: ${e.message}` }));
    this.proc.on("exit", (code) => {
      for (const p of this.pending.values()) p.reject({ code: "engine_exit", message: "The engine stopped." });
      this.pending.clear();
      this.proc = undefined;
      if (code !== 0) this.setState({ running: false, message: `The engine stopped (exit ${code}). ${errors.join("").slice(-400)}` });
    });
  }

  stop(): void {
    this.proc?.stdin.end(); // the engine stops what it started when its input closes
  }

  request<T>(op: string, params: Record<string, unknown> = {}, progressKey?: string): Promise<T> {
    const proc = this.proc;
    if (!proc) return Promise.reject({ code: "engine_down", message: this.state.message ?? "The engine is not running." });
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject, progressKey });
      proc.stdin.write(JSON.stringify({ ...params, id, op }) + "\n");
    });
  }

  private receive(line: string): void {
    let msg: { id: number; event?: string; line?: string; ok?: boolean; result?: unknown; error?: EngineError };
    try {
      msg = JSON.parse(line);
    } catch {
      return; // not protocol output
    }
    if (msg.event === "progress") {
      const key = this.pending.get(msg.id)?.progressKey;
      if (key) this.onProgress({ key, line: msg.line ?? "" });
      return;
    }
    const p = this.pending.get(msg.id);
    if (!p) return;
    this.pending.delete(msg.id);
    if (msg.ok) p.resolve(msg.result);
    else p.reject(msg.error ?? { code: "unknown", message: "Unknown error" });
  }

  private setState(s: EngineState): void {
    this.state = s;
    this.onState(s);
  }
}

function locate(): { java: string; classpath: string } {
  const exe = process.platform === "win32" ? "java.exe" : "java";
  if (app.isPackaged) {
    const res = process.resourcesPath;
    return { java: path.join(res, "runtime", "bin", exe), classpath: path.join(res, "engine", "lib", "*") };
  }
  const javaHome = process.env.ENVX_JAVA_HOME ?? process.env.JAVA_HOME;
  return {
    java: javaHome ? path.join(javaHome, "bin", exe) : "java",
    classpath: path.join(app.getAppPath(), "..", "engine", "build", "install", "envx", "lib", "*"),
  };
}
