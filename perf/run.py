#!/usr/bin/env python3
"""
HTTP benchmark for Ligero: the same products API in several frameworks,
measured identically, with two gates:

  1. regression  the build under test must not be slower than the baseline
                 build of Ligero (normally the PR's target branch);
  2. standing    the build under test must keep its position against the
                 other frameworks: its overall throughput divided by theirs
                 may not fall below the floors in thresholds.json.

Every app is started `ROUNDS` times, interleaved (A, B, C, A, B, C, ...) so
that drift on a shared machine hits all of them alike, and the median of the
rounds is what gets compared. Results go to stdout, to `results.md` and, when
running in GitHub Actions, to the job summary.

  python3 perf/run.py --candidate 0.0.0-pr --baseline 0.0.0-base

Versions are whatever was published with:
  ./gradlew publishToMavenLocal -Pversion=<version>-SNAPSHOT
"""
import argparse
import json
import os
import pathlib
import re
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
SCENARIOS = [
    ("list", "GET /products"),
    ("byId", "GET /products/{id}"),
    ("create", "POST /products"),
]


def log(message):
    print(message, flush=True)


def gradle():
    return shutil.which("gradle") or str(HERE.parent / "gradlew")


def build(app_dir, name, props, out_root):
    """installDist the app and copy the result aside (one source dir can be built several ways)."""
    target = out_root / name
    if target.exists():
        shutil.rmtree(target)
    command = [gradle(), "-q", "--console=plain", "clean", "installDist"] + [f"-P{k}={v}" for k, v in props.items()]
    if not shutil.which("gradle"):
        command[1:1] = ["-p", str(app_dir)]
    result = subprocess.run(command, cwd=app_dir, capture_output=True, text=True)
    if result.returncode != 0:
        log(result.stdout[-3000:] + result.stderr[-3000:])
        raise SystemExit(f"build failed: {name}")
    shutil.copytree(app_dir / "build" / "install" / app_dir.name, target)
    lib_mb = sum(f.stat().st_size for f in (target / "lib").glob("*.jar")) / 1e6
    return target / "bin" / app_dir.name, lib_mb


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_ready(port, process, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return False
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/products", timeout=1) as response:
                if response.status == 200:
                    return True
        except Exception:
            time.sleep(0.01)
    return False


def rss_mb(pid):
    try:
        for line in open(f"/proc/{pid}/status"):
            if line.startswith("VmRSS"):
                return int(line.split()[1]) / 1024
    except OSError:
        pass
    return 0.0


def load(port, connections, seconds, scenario):
    out = subprocess.run(
        ["java", "-Xmx512m", str(HERE / "Load.java"), str(port), str(connections), str(seconds), scenario],
        capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
    fields = dict(re.findall(r"(\w+)=([\w.]+)", out.stdout))
    if "throughput" not in fields:
        raise SystemExit(f"load driver failed: {out.stdout} {out.stderr[-2000:]}")
    return {"throughput": float(fields["throughput"]), "p50": float(fields["p50"]),
            "p99": float(fields["p99"]), "errors": int(fields["errors"])}


def run_once(binary, args):
    """One cold start of the app: startup time, then every scenario, then RSS."""
    port = free_port()
    env = {**os.environ, "JAVA_TOOL_OPTIONS": "", "JAVA_OPTS": "-Xms256m -Xmx256m"}
    started = time.monotonic()
    process = subprocess.Popen([str(binary), str(port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               env=env, start_new_session=True)
    try:
        if not wait_ready(port, process):
            raise SystemExit(f"{binary} did not become ready")
        sample = {"startup": (time.monotonic() - started) * 1000}
        # Reads first: `create` grows the store, which would change what `list` returns.
        load(port, args.connections, args.warmup, "list")
        load(port, args.connections, args.warmup, "byId")
        sample["list"] = load(port, args.connections, args.duration, "list")
        sample["byId"] = load(port, args.connections, args.duration, "byId")
        load(port, args.connections, max(1, args.warmup // 2), "create")
        sample["create"] = load(port, args.connections, args.duration, "create")
        for scenario, _ in SCENARIOS:
            if sample[scenario]["errors"] or sample[scenario]["throughput"] <= 0:
                raise SystemExit(f"{binary}: scenario '{scenario}' failed: {sample[scenario]}")
        sample["rss"] = rss_mb(process.pid)
        return sample
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def median(values):
    return statistics.median(values)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--candidate", required=True, help="Ligero version under test (in mavenLocal)")
    parser.add_argument("--baseline", help="Ligero version to compare against (in mavenLocal); omit to skip gate 1")
    parser.add_argument("--rounds", type=int, default=int(os.environ.get("ROUNDS", 3)))
    parser.add_argument("--duration", type=int, default=int(os.environ.get("DUR", 5)), help="seconds per scenario")
    parser.add_argument("--warmup", type=int, default=int(os.environ.get("WARMUP", 4)), help="seconds of warmup")
    parser.add_argument("--connections", type=int, default=int(os.environ.get("CONC", 32)))
    parser.add_argument("--max-regression", type=float, default=float(os.environ.get("MAX_REGRESSION", 0.10)),
                        help="gate 1: allowed throughput drop vs the baseline, per scenario (0.10 = 10%%)")
    parser.add_argument("--skip-others", action="store_true", help="only Ligero builds (no Javalin / Spring Boot)")
    parser.add_argument("--no-typed", action="store_true", help="skip the typed-controllers app")
    parser.add_argument("--no-gates", action="store_true", help="report only, always exit 0")
    args = parser.parse_args()

    out_root = HERE / "build"
    out_root.mkdir(exist_ok=True)
    apps = HERE / "apps"
    # (key, label, source dir, gradle properties, role)
    plan = [("candidate", "Ligero — this build (JDK engine)", apps / "ligero-app",
             {"ligeroVersion": args.candidate}, "candidate")]
    if args.baseline:
        plan.append(("baseline", "Ligero — baseline (JDK engine)", apps / "ligero-app",
                     {"ligeroVersion": args.baseline}, "baseline"))
    if not args.no_typed:
        plan.append(("typed", "Ligero — this build, typed controllers", apps / "ligero-typed-app",
                     {"ligeroVersion": args.candidate}, "info"))
    plan.append(("jetty", "Ligero — this build (Jetty engine)", apps / "ligero-app",
                 {"ligeroVersion": args.candidate, "engine": "jetty"}, "info"))
    if not args.skip_others:
        plan.append(("javalin", "Javalin 6 (Jetty)", apps / "javalin-app", {}, "other"))
        plan.append(("spring", "Spring Boot 3.4 (MVC / Tomcat)", apps / "springboot-app", {}, "other"))

    binaries, libs = {}, {}
    for key, label, source, props, _ in plan:
        log(f"building {label} ...")
        binaries[key], libs[key] = build(source, key, props, out_root)

    samples = {key: [] for key, *_ in plan}
    for round_number in range(1, args.rounds + 1):
        for key, label, *_ in plan:
            sample = run_once(binaries[key], args)
            samples[key].append(sample)
            log(f"round {round_number}/{args.rounds}  {label:44s} "
                + "  ".join(f"{s}={sample[s]['throughput']:.0f}/s" for s, _ in SCENARIOS)
                + f"  startup={sample['startup']:.0f}ms")

    stats = {}
    for key, *_ in plan:
        runs = samples[key]
        entry = {"startup": median([r["startup"] for r in runs]), "rss": median([r["rss"] for r in runs]),
                 "errors": sum(r[s]["errors"] for r in runs for s, _ in SCENARIOS)}
        for scenario, _ in SCENARIOS:
            entry[scenario] = {m: median([r[scenario][m] for r in runs]) for m in ("throughput", "p50", "p99")}
        # one number per app: geometric mean of the scenario throughputs
        product = 1.0
        for scenario, _ in SCENARIOS:
            product *= entry[scenario]["throughput"]
        entry["overall"] = product ** (1 / len(SCENARIOS))
        stats[key] = entry

    lines = ["## HTTP benchmark", "",
             f"{args.rounds} interleaved rounds, {args.connections} keep-alive connections, "
             f"{args.duration}s per scenario after warmup; medians. "
             f"{os.cpu_count()} CPUs. Throughput in requests/second (higher is better).", "",
             "| Framework | " + " | ".join(label for _, label in SCENARIOS)
             + " | Overall | p99 list (ms) | Startup (ms) | RSS (MB) | Libs (MB) |",
             "|---|" + "--:|" * (len(SCENARIOS) + 5)]
    for key, label, *_ in plan:
        e = stats[key]
        lines.append(f"| {label} | " + " | ".join(f"{e[s]['throughput']:,.0f}" for s, _ in SCENARIOS)
                     + f" | {e['overall']:,.0f} | {e['list']['p99']:.2f} | {e['startup']:.0f} | {e['rss']:.0f} | {libs[key]:.1f} |")
    lines.append("")

    failures = []
    candidate = stats["candidate"]
    if candidate["errors"]:
        failures.append(f"the candidate returned {candidate['errors']} failed requests under load")

    lines.append("### Gate 1 — no regression against the baseline")
    if args.baseline:
        baseline = stats["baseline"]
        lines += ["", "| Scenario | Baseline | This build | Change | |", "|---|--:|--:|--:|---|"]
        for scenario, label in SCENARIOS:
            before, after = baseline[scenario]["throughput"], candidate[scenario]["throughput"]
            change = after / before - 1
            ok = change >= -args.max_regression
            lines.append(f"| {label} | {before:,.0f} | {after:,.0f} | {change:+.1%} | {'✅' if ok else '❌'} |")
            if not ok:
                failures.append(f"{label}: {change:+.1%} vs the baseline (limit -{args.max_regression:.0%})")
    else:
        lines += ["", "_No baseline given — skipped._"]
    lines.append("")

    lines.append("### Gate 2 — standing against other frameworks")
    floors = json.loads((HERE / "thresholds.json").read_text())["min_ratio_vs"]
    others = [(key, label, stats[key]) for key, label, *_rest, role in plan if role == "other"]
    if others:
        lines += ["", "Overall throughput of this build divided by the other framework's; "
                  "it must not fall below the floor in `perf/thresholds.json`.", "",
                  "| Framework | Overall | Ligero vs it | Floor | |", "|---|--:|--:|--:|---|"]
        for key, label, other in others:
            ratio = candidate["overall"] / other["overall"]
            floor = floors[key]
            ok = ratio >= floor
            lines.append(f"| {label} | {other['overall']:,.0f} | {ratio:.2f}× | {floor:.2f}× | {'✅' if ok else '❌'} |")
            if not ok:
                failures.append(f"Ligero is at {ratio:.2f}× {label} overall, below the floor of {floor:.2f}×")
    else:
        lines += ["", "_Other frameworks not measured — skipped._"]
    lines.append("")

    if "typed" in stats:
        ratio = stats["typed"]["overall"] / candidate["overall"]
        lines += [f"Typed controllers run at {ratio:.2f}× the throughput of hand-written lambdas "
                  "(informational, not gated).", ""]

    lines.append("**Result: " + ("FAILED — " + "; ".join(failures) if failures else "passed") + "**")
    report = "\n".join(lines) + "\n"
    log("\n" + report)
    (HERE / "results.md").write_text(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as handle:
            handle.write(report)
    if failures and not args.no_gates:
        sys.exit(1)


if __name__ == "__main__":
    main()
