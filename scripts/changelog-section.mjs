#!/usr/bin/env node
// Prints the CHANGELOG.md section of one version, without its heading, for release notes.
// Fails when the version has no section or the section is empty.
// Usage: node scripts/changelog-section.mjs <version> [CHANGELOG.md]
import { readFileSync } from "node:fs";

const [version, file = "CHANGELOG.md"] = process.argv.slice(2);
if (!version) {
  console.error("usage: node scripts/changelog-section.mjs <version> [CHANGELOG.md]");
  process.exit(2);
}

const lines = readFileSync(file, "utf8").split("\n");
const start = lines.findIndex((line) => line.startsWith(`## [${version}]`));
if (start < 0) {
  console.error(`${file} has no section for ${version}; add "## [${version}] - <date>" first`);
  process.exit(1);
}
let end = lines.findIndex((line, index) => index > start && /^## \[/.test(line));
if (end < 0) end = lines.length;
const body = lines
  .slice(start + 1, end)
  .filter((line) => !/^\[[^\]]+\]: /.test(line))
  .join("\n")
  .trim();
if (!body) {
  console.error(`the ${version} section in ${file} is empty`);
  process.exit(1);
}
console.log(body);
