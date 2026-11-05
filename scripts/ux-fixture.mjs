#!/usr/bin/env node
// Builds a throwaway library fixture with long recordings so audiobook conversion takes long
// enough to watch the queue progress. Usage: node scripts/ux-fixture.mjs <target-dir>
import { execFileSync } from "node:child_process";
import { mkdirSync, copyFileSync, writeFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, "..", "build", "ux-data");
const target = process.argv[2];
if (!target) {
  console.error("usage: node scripts/ux-fixture.mjs <target-dir>");
  process.exit(2);
}
if (!existsSync(join(source, "library.json"))) {
  console.error(`missing ${source}/library.json - run the app once with AIRPLAY_CAPTURE_DATA=${source}`);
  process.exit(2);
}

mkdirSync(join(target, "library"), { recursive: true });
mkdirSync(join(target, "covers"), { recursive: true });
mkdirSync(join(target, "meta"), { recursive: true });
mkdirSync(join(target, "tmp"), { recursive: true });

for (const name of ["library.json", "settings.json"]) {
  copyFileSync(join(source, name), join(target, name));
}

const tracks = JSON.parse(
  execFileSync("cat", [join(source, "library.json")], { encoding: "utf8" }),
);

// One long audiobook recording (~40 min of quiet tone) so the split + encode is visible.
const long = join(target, "library", "Long Narrator - Das Grosse Buch.m4a");
if (!existsSync(long)) {
  execFileSync("ffmpeg", [
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "sine=frequency=220:duration=2400",
    "-c:a", "aac", "-b:a", "128k", long,
  ]);
}
tracks.push({
  id: "big1",
  title: "Das Grosse Buch",
  artist: "Long Narrator",
  album: "Das Grosse Buch",
  genre: "Audiobook",
  durationMs: 2_400_000,
  recordedAtEpochMs: Date.now(),
  baseName: "Long Narrator - Das Grosse Buch",
  files: { m4a: "Long Narrator - Das Grosse Buch.m4a" },
  sizeBytes: 40_000_000,
  status: "ready",
});

writeFileSync(join(target, "library.json"), JSON.stringify(tracks, null, 2));
console.log(`fixture ready in ${target}`);
