// Puts the engine and its own Java runtime into app/resources before packaging, so the installed app needs no Java.
// Needs a JDK 21 (JAVA_HOME) and the engine built (engine: ./gradlew installDist).
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const lib = path.resolve(app, "..", "engine", "build", "install", "sevli", "lib");
const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error("Set JAVA_HOME to a JDK 21");
if (!existsSync(lib)) throw new Error(`Build the engine first (engine: ./gradlew installDist); missing ${lib}`);

const engineOut = path.join(app, "resources", "engine", "lib");
rmSync(path.join(app, "resources", "engine"), { recursive: true, force: true });
mkdirSync(engineOut, { recursive: true });
for (const jar of readdirSync(lib).filter((f) => f.endsWith(".jar"))) cpSync(path.join(lib, jar), path.join(engineOut, jar));

// Modules from `jdeps --print-module-deps` on the engine jars, plus what jdeps cannot see: TLS ciphers for HTTPS
// downloads (jdk.crypto.ec), jar file systems used when remapping (jdk.zipfs), and extra charsets.
const modules = ["java.base", "java.compiler", "java.net.http", "java.sql", "jdk.crypto.ec", "jdk.zipfs", "jdk.charsets"];
const runtime = path.join(app, "resources", "runtime");
rmSync(runtime, { recursive: true, force: true });
const jlink = path.join(javaHome, "bin", process.platform === "win32" ? "jlink.exe" : "jlink");
execFileSync(jlink, ["--add-modules", modules.join(","), "--strip-debug", "--no-header-files", "--no-man-pages",
  "--compress", "zip-6", "--output", runtime], { stdio: "inherit" });
console.log(`engine: ${readdirSync(engineOut).length} jars; runtime: ${runtime}`);
