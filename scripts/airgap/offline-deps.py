#!/usr/bin/env python3
"""
offline-deps.py — build and ship a complete offline Gradle dependency bundle.

  download   Resolve every dependency/plugin (jars + poms + .module + -sources + -javadoc)
             for all subprojects on an internet-connected machine, grab the Gradle
             distribution, and zip it all up.

  artifact   Download one or more Maven artifacts by their Gradle implementation
             string (group:artifact:version) straight from a Maven repository —
             no Gradle project required — and zip them into the same bundle format.

  upload     Push a previously built bundle into Artifactory from inside the air-gapped
             network using the JFrog CLI (jf).

See README-airgap.md (generated into the bundle) for the full air-gapped workflow.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent


# ── Configuration (override via environment) ─────────────────────────────────
@dataclass
class Config:
    gradle_version: str
    gradle_dist_file: str
    offline_guh: Path           # isolated Gradle home: cache holds ONLY this project's deps
    stage_dir: Path             # bundle staging dir
    offline_repo_dir: Path      # Maven-layout repo inside the bundle
    gradle_dist_dir: Path       # holds the Gradle distribution zip
    output_zip: Path            # final bundle zip
    artifactory_url: str
    artifactory_maven_repo: str
    artifactory_generic_repo: str
    artifactory_token: str
    artifactory_user: str
    artifactory_password: str
    jfrog_server_id: str


def _env(name: str, default: str) -> str:
    """Return $name, treating an empty value as unset (mirrors bash ${VAR:-default})."""
    return os.environ.get(name) or default


def load_config() -> Config:
    gradle_version = _env("GRADLE_VERSION", "9.5.0")
    stage_dir = Path(_env("STAGE_DIR", str(REPO_ROOT / "build" / "offline-bundle")))
    today = datetime.now().strftime("%Y%m%d")
    return Config(
        gradle_version=gradle_version,
        gradle_dist_file=f"gradle-{gradle_version}-bin.zip",
        offline_guh=Path(_env("OFFLINE_GUH", str(REPO_ROOT / ".offline-gradle-home"))),
        stage_dir=stage_dir,
        offline_repo_dir=stage_dir / "offline-repo",
        gradle_dist_dir=stage_dir / "gradle-dist",
        output_zip=Path(_env("OUTPUT_ZIP", str(REPO_ROOT / f"monk-offline-deps-{today}.zip"))),
        artifactory_url=os.environ.get("ARTIFACTORY_URL", ""),
        artifactory_maven_repo=_env("ARTIFACTORY_MAVEN_REPO", "monk-offline-maven"),
        artifactory_generic_repo=_env("ARTIFACTORY_GENERIC_REPO", "monk-offline-generic"),
        artifactory_token=os.environ.get("ARTIFACTORY_TOKEN", ""),
        artifactory_user=os.environ.get("ARTIFACTORY_USER", ""),
        artifactory_password=os.environ.get("ARTIFACTORY_PASSWORD", ""),
        jfrog_server_id=os.environ.get("JFROG_SERVER_ID", ""),
    )


# ── Logging ──────────────────────────────────────────────────────────────────
def log(msg: str) -> None:
    print(f"\033[1;34m==>\033[0m {msg}")


def err(msg: str) -> None:
    print(f"\033[1;31mERROR:\033[0m {msg}", file=sys.stderr)


def die(msg: str) -> None:
    err(msg)
    sys.exit(1)


def run(cmd: list, cwd: Path | None = None) -> None:
    """Run an external command, exiting cleanly (no traceback) on failure."""
    proc = subprocess.run([str(c) for c in cmd], cwd=cwd)
    if proc.returncode != 0:
        die(f"Command failed ({proc.returncode}): {' '.join(str(c) for c in cmd)}")


def human_size(num_bytes: int) -> str:
    """Approximate `du -h` output (1024-based, single-letter suffixes)."""
    size = float(num_bytes)
    for unit in ("B", "K", "M", "G"):
        if size < 1024:
            return f"{int(size)}{unit}" if unit == "B" else f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}T"


# ── download ─────────────────────────────────────────────────────────────────
def run_gradle_resolution(cfg: Config, skip_tests: bool) -> None:
    gradle_args = [
        "-g", cfg.offline_guh,
        "-I", SCRIPT_DIR / "download-offline.init.gradle.kts",
        "--refresh-dependencies",
        "--no-configuration-cache",
        "quarkusGenerateDevAppModel",
        "resolveOfflineDependencies",
    ]
    if skip_tests:
        gradle_args += ["-x", "test"]
        log("Resolving dependencies WITHOUT running tests (some test-time deps may be missed).")
    log(f"Populating isolated Gradle cache at {cfg.offline_guh}")
    run([REPO_ROOT / "gradlew", *gradle_args], cwd=REPO_ROOT)


def harvest_cache(cfg: Config) -> None:
    files_root = cfg.offline_guh / "caches" / "modules-2" / "files-2.1"
    if not files_root.is_dir():
        die(f"Gradle cache not found at {files_root} (did the resolution step run?)")

    log(f"Harvesting cache into Maven layout: {cfg.offline_repo_dir}")
    if cfg.offline_repo_dir.exists():
        shutil.rmtree(cfg.offline_repo_dir)
    cfg.offline_repo_dir.mkdir(parents=True, exist_ok=True)

    count = 0
    for src in files_root.rglob("*"):
        if not src.is_file():
            continue
        # Cache layout: files-2.1/<group>/<artifact>/<version>/<sha1>/<filename>
        parts = src.relative_to(files_root).parts
        if len(parts) < 5:
            continue
        group, artifact, version = parts[0], parts[1], parts[2]
        filename = Path(*parts[4:])  # strip the <sha1>/ segment
        dest_dir = cfg.offline_repo_dir.joinpath(*group.split("."), artifact, version)
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / filename
        if not dest.exists():  # cp -n: never clobber
            shutil.copyfile(src, dest)
        count += 1

    if count == 0:
        die("No artifacts harvested — cache appears empty.")
    log(f"Harvested {count} files.")


def read_distribution_url(props_path: Path) -> str:
    """Read distributionUrl from gradle-wrapper.properties, unescaping the `\\:`."""
    for line in props_path.read_text(encoding="utf-8").splitlines():
        if line.startswith("distributionUrl="):
            return line.split("=", 1)[1].replace("\\:", ":").strip()
    return ""


def download(url: str, dest: Path, retries: int = 3) -> None:
    """Download url -> dest, following redirects and retrying on failure (curl -fL --retry 3)."""
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(url) as resp, open(dest, "wb") as out:
                shutil.copyfileobj(resp, out)
            return
        except Exception as exc:  # noqa: BLE001 — retry on any transport/HTTP error
            last_error = exc
            if attempt < retries:
                log(f"Download failed (attempt {attempt}/{retries}): {exc}; retrying…")
    die(f"Failed to download {url}: {last_error}")


def fetch_gradle_dist(cfg: Config) -> None:
    cfg.gradle_dist_dir.mkdir(parents=True, exist_ok=True)
    props = REPO_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
    url = read_distribution_url(props)
    if not url:
        die(f"Could not read distributionUrl from {props}")
    log(f"Downloading Gradle distribution: {url}")
    download(url, cfg.gradle_dist_dir / cfg.gradle_dist_file)


def write_readme(cfg: Config) -> None:
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    readme = f"""# monk offline dependency bundle

Generated {generated} for Gradle {cfg.gradle_version}.

Contents:
- `offline-repo/`            Maven-layout repository (jars, poms, .module, -sources, -javadoc, plugin markers)
- `gradle-dist/{cfg.gradle_dist_file}`  Gradle distribution
- `offline.init.gradle.kts`  Init script that repoints Gradle at Artifactory

## 1. Upload into Artifactory (run inside the air-gapped network)

Create two Artifactory repos: a **Maven** repo (e.g. `{cfg.artifactory_maven_repo}`) and a
**generic** repo (e.g. `{cfg.artifactory_generic_repo}`). Then:

    export ARTIFACTORY_URL="https://artifactory.internal/artifactory"
    export ARTIFACTORY_TOKEN="<access-token>"
    ./offline-deps.py upload .          # or: ./offline-deps.py upload monk-offline-deps-<date>.zip

## 2. Point the build at Artifactory

a) Wrapper distribution — set in `gradle/wrapper/gradle-wrapper.properties`:

    distributionUrl=https\\://artifactory.internal/artifactory/{cfg.artifactory_generic_repo}/gradle/distributions/{cfg.gradle_dist_file}

b) Dependencies + plugins — build with the bundled init script (do NOT pass --offline):

    export ARTIFACTORY_MAVEN_URL="https://artifactory.internal/artifactory/{cfg.artifactory_maven_repo}"
    ./gradlew -I offline.init.gradle.kts build

## Prerequisite: JDK 25

The build uses a Java 25 toolchain. This project configures no toolchain download resolver,
so Gradle will NOT try to fetch a JDK — it requires JDK 25 to already be installed on the
machine. Ensure a JDK 25 is present (and discoverable, e.g. via JAVA_HOME or
`org.gradle.java.installations.paths`).
"""
    (cfg.stage_dir / "README-airgap.md").write_text(readme, encoding="utf-8")


def make_bundle_zip(cfg: Config) -> None:
    """Zip the staged bundle, with paths relative to stage_dir."""
    items = ["offline-repo", "gradle-dist", "offline.init.gradle.kts", "README-airgap.md"]
    with zipfile.ZipFile(cfg.output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for item in items:
            path = cfg.stage_dir / item
            if path.is_dir():
                for child in sorted(path.rglob("*")):
                    if child.is_file():
                        zf.write(child, child.relative_to(cfg.stage_dir))
            elif path.is_file():
                zf.write(path, path.relative_to(cfg.stage_dir))


def cmd_download(cfg: Config, skip_tests: bool) -> None:
    cfg.stage_dir.mkdir(parents=True, exist_ok=True)
    run_gradle_resolution(cfg, skip_tests)
    harvest_cache(cfg)
    fetch_gradle_dist(cfg)
    shutil.copyfile(
        SCRIPT_DIR / "offline.init.gradle.kts",
        cfg.stage_dir / "offline.init.gradle.kts",
    )
    write_readme(cfg)

    log(f"Zipping bundle -> {cfg.output_zip}")
    cfg.output_zip.unlink(missing_ok=True)
    make_bundle_zip(cfg)
    log(f"Done. Bundle: {cfg.output_zip} ({human_size(cfg.output_zip.stat().st_size)})")


# ── upload ───────────────────────────────────────────────────────────────────
def build_auth(cfg: Config) -> list:
    if cfg.jfrog_server_id:
        return [f"--server-id={cfg.jfrog_server_id}"]

    if not cfg.artifactory_url:
        die("Set ARTIFACTORY_URL or JFROG_SERVER_ID.")
    auth = [f"--url={cfg.artifactory_url}"]
    if cfg.artifactory_token:
        auth.append(f"--access-token={cfg.artifactory_token}")
    elif cfg.artifactory_user:
        auth += [f"--user={cfg.artifactory_user}", f"--password={cfg.artifactory_password}"]
    else:
        die("Set ARTIFACTORY_TOKEN, or ARTIFACTORY_USER + ARTIFACTORY_PASSWORD.")
    return auth


def cmd_upload(cfg: Config, bundle: str | None) -> None:
    cleanup: Path | None = None

    if bundle and Path(bundle).is_file():
        workdir = Path(tempfile.mkdtemp())
        cleanup = workdir
        log(f"Expanding {bundle} -> {workdir}")
        with zipfile.ZipFile(bundle) as zf:
            zf.extractall(workdir)
    elif bundle and Path(bundle).is_dir():
        workdir = Path(bundle)
    elif cfg.stage_dir.is_dir():
        workdir = cfg.stage_dir
    else:
        die(f"Provide a bundle zip or directory (or run from a machine that has {cfg.stage_dir}).")

    if shutil.which("jf") is None:
        die("jf (JFrog CLI) not found on PATH.")
    if not (workdir / "offline-repo").is_dir():
        die(f"offline-repo/ not found in {workdir}")

    auth = build_auth(cfg)

    log(f"Uploading Maven artifacts -> {cfg.artifactory_maven_repo}")
    run(
        ["jf", "rt", "upload", *auth, "offline-repo/(**)", f"{cfg.artifactory_maven_repo}/{{1}}"],
        cwd=workdir,
    )

    dist = workdir / "gradle-dist" / cfg.gradle_dist_file
    if dist.is_file():
        log(f"Uploading Gradle distribution -> {cfg.artifactory_generic_repo}")
        run(
            ["jf", "rt", "upload", *auth,
             f"gradle-dist/{cfg.gradle_dist_file}",
             f"{cfg.artifactory_generic_repo}/gradle/distributions/"],
            cwd=workdir,
        )

    if cleanup is not None:
        shutil.rmtree(cleanup)
    log("Upload complete.")


# ── artifact ─────────────────────────────────────────────────────────────────
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"


@dataclass
class Coordinate:
    """A Maven coordinate parsed from Gradle notation: group:artifact:version[:classifier][@ext]."""
    group: str
    artifact: str
    version: str
    classifier: str | None
    extension: str

    @property
    def path(self) -> str:  # group/with/slashes/artifact/version
        return "/".join([*self.group.split("."), self.artifact, self.version])

    def filename(self, classifier: str | None = None, extension: str | None = None) -> str:
        suffix = f"-{classifier}" if classifier else ""
        return f"{self.artifact}-{self.version}{suffix}.{extension or self.extension}"

    def __str__(self) -> str:
        spec = f"{self.group}:{self.artifact}:{self.version}"
        if self.classifier:
            spec += f":{self.classifier}"
        return spec + (f"@{self.extension}" if self.extension != "jar" else "")


def parse_coordinate(spec: str) -> Coordinate:
    spec = spec.strip()
    extension = "jar"
    if "@" in spec:
        spec, extension = spec.rsplit("@", 1)
    parts = spec.split(":")
    if len(parts) < 3 or not all(parts[:3]):
        die(f"Invalid coordinate '{spec}': expected group:artifact:version[:classifier][@ext]")
    classifier = parts[3] if len(parts) >= 4 and parts[3] else None
    return Coordinate(parts[0], parts[1], parts[2], classifier, extension)


def download_optional(url: str, dest: Path) -> bool:
    """Best-effort download: return False (leaving nothing behind) instead of dying on 404/error."""
    try:
        with urllib.request.urlopen(url) as resp, open(dest, "wb") as out:
            shutil.copyfileobj(resp, out)
        return True
    except Exception:  # noqa: BLE001 — optional file; its absence is not fatal
        dest.unlink(missing_ok=True)
        return False


def fetch_artifact(cfg: Config, coord: Coordinate, repo_url: str, with_sources: bool) -> int:
    """Download one coordinate into the Maven-layout offline-repo. Returns the file count."""
    base = f"{repo_url.rstrip('/')}/{coord.path}"
    dest_dir = cfg.offline_repo_dir.joinpath(*coord.group.split("."), coord.artifact, coord.version)
    dest_dir.mkdir(parents=True, exist_ok=True)

    # The main artifact is required; the pom, .module, and -sources/-javadoc jars are best-effort.
    main = coord.filename(classifier=coord.classifier)
    main_dest = dest_dir / main
    if main_dest.exists():
        log(f"  {main} already present")
    else:
        log(f"  {main}")
        download(f"{base}/{main}", main_dest)
    count = 1

    optionals = [] if coord.extension == "pom" else [coord.filename(extension="pom")]
    optionals.append(coord.filename(extension="module"))
    if with_sources:
        optionals += [coord.filename(classifier="sources", extension="jar"),
                      coord.filename(classifier="javadoc", extension="jar")]

    for name in optionals:
        dest = dest_dir / name
        if dest.exists():
            count += 1
        elif download_optional(f"{base}/{name}", dest):
            log(f"  {name}")
            count += 1

    return count


def prompt_coordinates() -> list[str]:
    log("Enter Gradle implementation strings (group:artifact:version), one per line; blank line to finish:")
    coords: list[str] = []
    try:
        while True:
            line = input("  > ").strip()
            if not line:
                break
            coords.append(line)
    except EOFError:
        pass
    return coords


def cmd_artifact(cfg: Config, coords: list[str], repo_url: str, with_sources: bool) -> None:
    coords = coords or prompt_coordinates()
    if not coords:
        die("No coordinates provided.")

    cfg.offline_repo_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    for spec in coords:
        coord = parse_coordinate(spec)
        log(f"Fetching {coord} from {repo_url}")
        total += fetch_artifact(cfg, coord, repo_url, with_sources)
    log(f"Staged {total} files into {cfg.offline_repo_dir}")

    log(f"Zipping bundle -> {cfg.output_zip}")
    cfg.output_zip.unlink(missing_ok=True)
    make_bundle_zip(cfg)
    log(f"Done. Bundle: {cfg.output_zip} ({human_size(cfg.output_zip.stat().st_size)})")


# ── CLI ──────────────────────────────────────────────────────────────────────
def build_parser(cfg: Config) -> argparse.ArgumentParser:
    epilog = f"""\
Environment (download):
  GRADLE_VERSION   Gradle version of the wrapper distribution (default: {cfg.gradle_version})
  OFFLINE_GUH      Isolated Gradle user home (default: {cfg.offline_guh})
  STAGE_DIR        Bundle staging dir (default: {cfg.stage_dir})
  OUTPUT_ZIP       Output zip path (default: monk-offline-deps-<date>.zip)

Environment (upload):
  ARTIFACTORY_URL          Base Artifactory URL, e.g. https://artifactory.internal/artifactory
  ARTIFACTORY_MAVEN_REPO   Target Maven repo key (default: {cfg.artifactory_maven_repo})
  ARTIFACTORY_GENERIC_REPO Target generic repo key for the Gradle dist (default: {cfg.artifactory_generic_repo})
  ARTIFACTORY_TOKEN        Access token (preferred), or
  ARTIFACTORY_USER / ARTIFACTORY_PASSWORD
  JFROG_SERVER_ID          Use a preconfigured 'jf' server-id instead of URL/credentials.

Environment (artifact):
  MAVEN_REPO_URL   Maven repo base URL to download from (default: {MAVEN_CENTRAL})
                   OUTPUT_ZIP / STAGE_DIR are shared with 'download'.
"""
    parser = argparse.ArgumentParser(
        prog=Path(__file__).name,
        description=__doc__,
        epilog=epilog,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = parser.add_subparsers(dest="mode")

    dl = sub.add_parser(
        "download",
        help="Resolve all dependencies and produce the offline bundle zip.",
    )
    dl.add_argument(
        "--no-tests",
        action="store_true",
        help="Skip test execution (faster; may miss some test-time deps).",
    )

    up = sub.add_parser(
        "upload",
        help="Upload a bundle to Artifactory via the JFrog CLI (jf).",
    )
    up.add_argument(
        "bundle",
        nargs="?",
        help="Bundle zip or directory to upload (default: STAGE_DIR).",
    )

    art = sub.add_parser(
        "artifact",
        help="Download Maven artifacts by Gradle coordinate (no Gradle project needed) and zip them.",
    )
    art.add_argument(
        "coords",
        nargs="*",
        metavar="group:artifact:version",
        help="One or more Gradle implementation strings. If omitted, you'll be prompted to enter them.",
    )
    art.add_argument(
        "--repo",
        default=_env("MAVEN_REPO_URL", MAVEN_CENTRAL),
        help=f"Maven repository base URL to download from (default: {MAVEN_CENTRAL}).",
    )
    art.add_argument(
        "--no-sources",
        action="store_true",
        help="Skip the -sources and -javadoc jars (fetch only the main artifact, pom, and .module).",
    )
    art.add_argument(
        "-o", "--output",
        help="Output bundle zip path (default: same as download, $OUTPUT_ZIP).",
    )
    return parser


def main(argv: list[str]) -> None:
    cfg = load_config()
    parser = build_parser(cfg)
    args = parser.parse_args(argv)

    if args.mode == "download":
        cmd_download(cfg, args.no_tests)
    elif args.mode == "upload":
        cmd_upload(cfg, args.bundle)
    elif args.mode == "artifact":
        if args.output:
            cfg.output_zip = Path(args.output)
        cmd_artifact(cfg, args.coords, args.repo, not args.no_sources)
    else:
        parser.print_help()


if __name__ == "__main__":
    main(sys.argv[1:])
