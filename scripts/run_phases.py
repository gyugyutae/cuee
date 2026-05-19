#!/usr/bin/env python3
"""Harness phase runner (codex CLI edition).

Reads tasks/{task-dir}/index.json, finds the next pending phase, spawns a
fresh codex CLI session with the phase prompt embedded, and updates status.

Usage:
    python scripts/run_phases.py <task-dir>
    # example:
    python scripts/run_phases.py 0-mvp

Environment:
    CODEX_BIN     Path to codex CLI (default: "codex" from PATH)
    CODEX_MODEL   Optional model to pass to `codex exec --model`
    CODEX_PERMISSION_FLAGS
                  Permission flags passed to `codex exec`
                  (default: --dangerously-bypass-approvals-and-sandbox)
    PHASE_TIMEOUT Per-phase timeout in seconds (default: 1800)
    SKIP_GIT      If "1", do not attempt git commits or baseline tracking
"""

from __future__ import annotations

import itertools
import json
import os
import shlex
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from _utils import KST, find_project_root, now_iso  # noqa: F401

ROOT = find_project_root()
TASKS_DIR = ROOT / "tasks"
TOP_INDEX_FILE = TASKS_DIR / "index.json"

def default_codex_bin() -> str:
    if os.name == "nt":
        candidates = [
            os.environ.get("CODEX_BIN"),
            os.path.expandvars(
                r"%LOCALAPPDATA%\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\OpenAI\Codex\bin\codex.exe"
            ),
            os.path.expandvars(r"%USERPROFILE%\.codex\.sandbox-bin\codex.exe"),
            shutil.which("codex.exe"),
            shutil.which("codex"),
        ]
        for candidate in candidates:
            if candidate and Path(candidate).exists():
                return candidate
        return "codex.exe"
    return os.environ.get("CODEX_BIN") or shutil.which("codex") or "codex"


CODEX_BIN = default_codex_bin()
CODEX_MODEL = os.environ.get("CODEX_MODEL")
CODEX_PERMISSION_FLAGS = shlex.split(os.environ.get(
    "CODEX_PERMISSION_FLAGS",
    "--dangerously-bypass-approvals-and-sandbox",
))
PHASE_TIMEOUT = int(os.environ.get("PHASE_TIMEOUT", "1800"))
SKIP_GIT = os.environ.get("SKIP_GIT", "0") == "1"

COMMIT_MSG_TEMPLATE = "feat({task_name}): phase {phase_num} - {phase_name}"
RUNNER_COMMIT_MSG_TEMPLATE = (
    "chore({task_name}): phase {phase_num} output + timestamps"
)
SPINNER_CHARS = "|/-\\"


# ---------------------------------------------------------------------------
# index helpers
# ---------------------------------------------------------------------------

def load_index(index_file: Path) -> dict:
    with open(index_file, "r", encoding="utf-8") as f:
        return json.load(f)


def save_index(index_file: Path, data: dict) -> None:
    with open(index_file, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)


def find_next_phase(index: dict) -> Optional[dict]:
    for phase in index["phases"]:
        if phase["status"] == "pending":
            return phase
    return None


def get_last_phase(index: dict) -> Optional[dict]:
    for phase in reversed(index["phases"]):
        if phase["status"] != "pending":
            return phase
    return None


def get_task_dir() -> Path:
    if len(sys.argv) < 2:
        print("Usage: python scripts/run_phases.py <task-dir>")
        print("Example: python scripts/run_phases.py 0-mvp")
        sys.exit(1)
    task_dir = TASKS_DIR / sys.argv[1]
    if not task_dir.is_dir():
        print(f"ERROR: Task directory not found: {task_dir}")
        sys.exit(1)
    return task_dir


def load_phase_prompt(task_dir: Path, phase_num: int) -> str:
    phase_file = task_dir / f"phase{phase_num}.md"
    if not phase_file.exists():
        print(f"ERROR: {phase_file} not found")
        sys.exit(1)
    return phase_file.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# codex CLI
# ---------------------------------------------------------------------------

def codex_available() -> bool:
    return shutil.which(CODEX_BIN) is not None or Path(CODEX_BIN).exists()


def build_preamble(project_name: str, task_dir_name: str, task_name: str) -> str:
    commit_example = COMMIT_MSG_TEMPLATE.format(
        task_name=task_name, phase_num="N", phase_name="<phase-name>"
    )
    return f"""당신은 {project_name} 프로젝트의 개발자입니다. 아래 phase의 작업을 수행하세요.

중요한 규칙:
1. 작업 전에 반드시 phase 파일이 명시한 사전 준비 문서들을 읽고 전체 설계를 이해하세요.
2. 이전 phase에서 작성된 코드를 꼼꼼히 읽고, 기존 코드와의 일관성을 유지하세요.
3. AC 검증을 직접 수행하고, 통과/실패에 따라 /tasks/{task_dir_name}/index.json을 업데이트하세요.
   - 통과: 해당 phase의 status를 "completed"로 변경.
   - 3회 이상 시도해도 실패: status를 "error"로 변경하고 "error_message" 필드 추가.
4. 불필요한 파일이나 코드를 추가하지 마세요. phase에 명시된 것만 작업하세요.
5. 기존 테스트를 깨뜨리지 마세요.
6. AC 통과 + index.json 업데이트 완료 후, 모든 변경사항을 아래 형식으로 커밋하세요 (git 저장소인 경우):
   {commit_example}

아래는 이번 phase의 상세 내용입니다:

"""


def run_codex(full_prompt: str, last_msg_path: Path) -> subprocess.CompletedProcess:
    cmd = [
        CODEX_BIN,
        "exec",
        "--skip-git-repo-check",
        *CODEX_PERMISSION_FLAGS,
    ]
    if CODEX_MODEL:
        cmd.extend(["--model", CODEX_MODEL])
    cmd.extend([
        "--output-last-message", str(last_msg_path),
        full_prompt,
    ])
    return subprocess.run(
        cmd,
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        timeout=PHASE_TIMEOUT,
    )


def run_phase(task_dir: Path, phase: dict, preamble: str) -> dict:
    phase_num = phase["phase"]
    phase_name = phase["name"]
    prompt_content = load_phase_prompt(task_dir, phase_num)
    full_prompt = preamble + prompt_content

    output_file = task_dir / f"phase{phase_num}-output.json"
    last_msg_file = task_dir / f"phase{phase_num}-last-message.txt"

    result = run_codex(full_prompt, last_msg_file)

    output_data = {
        "phase": phase_num,
        "name": phase_name,
        "exitCode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "lastMessage": last_msg_file.read_text(encoding="utf-8")
        if last_msg_file.exists()
        else "",
    }

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(output_data, f, indent=2, ensure_ascii=False)

    if result.returncode != 0:
        print(f"\n  WARN: codex exited with code {result.returncode}")
        if result.stderr:
            print(f"  stderr: {result.stderr[:500]}")

    return output_data


# ---------------------------------------------------------------------------
# git helpers (best-effort, silent if not a git repo)
# ---------------------------------------------------------------------------

def _is_git_repo() -> bool:
    if SKIP_GIT:
        return False
    return (ROOT / ".git").exists()


def git_run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
    )


def git_baseline() -> str:
    if not _is_git_repo():
        return ""
    r = git_run("rev-parse", "HEAD")
    return r.stdout.strip() if r.returncode == 0 else ""


def git_commit_docs(task_name: str) -> None:
    if not _is_git_repo():
        return
    git_run("add", "tasks/", "docs/", "prompts/", ".codex/")
    if git_run("diff", "--cached", "--quiet").returncode == 0:
        return
    msg = f"docs: create {task_name} plan"
    r = git_run("commit", "-m", msg)
    if r.returncode == 0:
        print(f"  + {msg}")
    else:
        print(f"  WARN: docs commit failed: {r.stderr.strip()}")


def git_commit_phase(task_name: str, task_dir_name: str, phase_num: int, phase_name: str) -> None:
    if not _is_git_repo():
        return

    output_file = f"tasks/{task_dir_name}/phase{phase_num}-output.json"
    last_msg_file = f"tasks/{task_dir_name}/phase{phase_num}-last-message.txt"
    index_file = f"tasks/{task_dir_name}/index.json"
    top_index = "tasks/index.json"

    # Step 1: codex fallback commit (code changes codex did not commit itself)
    git_run("add", "-A")
    git_run("reset", "HEAD", "--", output_file)
    git_run("reset", "HEAD", "--", last_msg_file)
    git_run("reset", "HEAD", "--", index_file)
    git_run("reset", "HEAD", "--", top_index)

    if git_run("diff", "--cached", "--quiet").returncode != 0:
        msg = COMMIT_MSG_TEMPLATE.format(
            task_name=task_name, phase_num=phase_num, phase_name=phase_name
        )
        r = git_run("commit", "-m", msg)
        if r.returncode != 0:
            print(f"  WARN: fallback commit failed: {r.stderr.strip()}")

    # Step 2: runner housekeeping commit
    git_run("add", "-A")
    if git_run("diff", "--cached", "--quiet").returncode != 0:
        msg = RUNNER_COMMIT_MSG_TEMPLATE.format(
            task_name=task_name, phase_num=phase_num
        )
        r = git_run("commit", "-m", msg)
        if r.returncode != 0:
            print(f"  WARN: housekeeping commit failed: {r.stderr.strip()}")


# ---------------------------------------------------------------------------
# top-level index
# ---------------------------------------------------------------------------

def update_top_index_status(task_dir_name: str, status: str) -> None:
    if not TOP_INDEX_FILE.exists():
        return
    top_index = load_index(TOP_INDEX_FILE)
    ts = now_iso()
    for task in top_index.get("tasks", []):
        if task.get("dir") == task_dir_name:
            task["status"] = status
            if status == "completed":
                task["completed_at"] = ts
            elif status == "error":
                task["failed_at"] = ts
            break
    save_index(TOP_INDEX_FILE, top_index)


# ---------------------------------------------------------------------------
# spinner
# ---------------------------------------------------------------------------

class Spinner:
    def __init__(self, message: str) -> None:
        self._message = message
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._spin, daemon=True)
        self._start_time = 0.0

    def _spin(self) -> None:
        chars = itertools.cycle(SPINNER_CHARS)
        while not self._stop.is_set():
            elapsed = int(time.monotonic() - self._start_time)
            sys.stderr.write(f"\r{next(chars)} {self._message} [{elapsed}s]")
            sys.stderr.flush()
            self._stop.wait(0.1)
        sys.stderr.write("\r" + " " * (len(self._message) + 24) + "\r")
        sys.stderr.flush()

    def __enter__(self) -> "Spinner":
        self._start_time = time.monotonic()
        self._thread.start()
        return self

    def __exit__(self, *_) -> None:
        self._stop.set()
        self._thread.join()

    @property
    def elapsed(self) -> float:
        return time.monotonic() - self._start_time


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> None:
    if not codex_available():
        print(
            f"ERROR: codex CLI not found at '{CODEX_BIN}'. "
            f"Install codex CLI or set CODEX_BIN env var."
        )
        sys.exit(1)

    task_dir = get_task_dir()
    task_dir_name = task_dir.name
    index_file = task_dir / "index.json"

    if not index_file.exists():
        print(f"ERROR: {index_file} not found")
        sys.exit(1)

    index = load_index(index_file)
    project_name = index.get("project", "harness")
    task_name = index.get("task", task_dir_name)
    total_phases = index.get("totalPhases", len(index["phases"]))
    pending_count = sum(1 for p in index["phases"] if p["status"] == "pending")

    print(f"\n{'=' * 60}")
    print(f"  Phase Runner ({CODEX_BIN}, model={CODEX_MODEL or 'default'})")
    print(f"  Task: {task_name} | Phases: {total_phases} | Pending: {pending_count}")
    print(f"{'=' * 60}")

    last = get_last_phase(index)
    if last and last["status"] == "error":
        print(f"\n  X Phase {last['phase']} ({last['name']}) failed.")
        if "error_message" in last:
            print(f"     Error: {last['error_message']}")
        print(
            f"     Fix the issue and reset status to 'pending' in {index_file} to retry."
        )
        sys.exit(1)

    git_commit_docs(task_name)
    preamble = build_preamble(project_name, task_dir_name, task_name)

    if "created_at" not in index:
        index["created_at"] = now_iso()
        save_index(index_file, index)
        if TOP_INDEX_FILE.exists():
            top_index = load_index(TOP_INDEX_FILE)
            for task in top_index.get("tasks", []):
                if task.get("dir") == task_dir_name and "created_at" not in task:
                    task["created_at"] = index["created_at"]
                    save_index(TOP_INDEX_FILE, top_index)
                    break

    baseline = git_baseline()

    while True:
        index = load_index(index_file)
        phase = find_next_phase(index)
        if phase is None:
            print("\n  All phases completed!")
            break

        phase_num = phase["phase"]
        phase_name = phase["name"]
        done_count = sum(1 for p in index["phases"] if p["status"] == "completed")

        ts_start = now_iso()
        for p in index["phases"]:
            if p["phase"] == phase_num and "started_at" not in p:
                p["started_at"] = ts_start
                save_index(index_file, index)
                break

        with Spinner(
            f"Phase {phase_num}/{total_phases - 1} ({done_count} done): {phase_name}"
        ) as sp:
            run_phase(task_dir, phase, preamble)
            elapsed = int(sp.elapsed)

        fresh_index = load_index(index_file)
        status = "pending"
        for p in fresh_index["phases"]:
            if p["phase"] == phase_num:
                status = p.get("status", "pending")
                break

        ts_end = now_iso()

        if status == "error":
            for p in fresh_index["phases"]:
                if p["phase"] == phase_num:
                    p["failed_at"] = ts_end
                    break
            save_index(index_file, fresh_index)
            print(f"  X Phase {phase_num}: {phase_name} failed [{elapsed}s]")
            for p in fresh_index["phases"]:
                if p["phase"] == phase_num and "error_message" in p:
                    print(f"     Error: {p['error_message']}")
                    break
            print(
                f"     Fix the issue and reset status to 'pending' in {index_file} to retry."
            )
            update_top_index_status(task_dir_name, "error")
            sys.exit(1)

        if status == "completed":
            for p in fresh_index["phases"]:
                if p["phase"] == phase_num:
                    p["completed_at"] = ts_end
                    break
            save_index(index_file, fresh_index)

            if phase_num == 0 and baseline:
                subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / "scripts" / "gen_docs_diff.py"),
                        str(task_dir),
                        baseline,
                    ],
                    cwd=str(ROOT),
                )

            git_commit_phase(task_name, task_dir_name, phase_num, phase_name)
            print(f"  + Phase {phase_num}: {phase_name} completed [{elapsed}s]")
        elif status == "pending":
            print(
                f"  X Phase {phase_num}: {phase_name} - status still 'pending' after execution"
            )
            print("     codex did not update index.json. Marking as error.")
            for p in fresh_index["phases"]:
                if p["phase"] == phase_num:
                    p["status"] = "error"
                    p["error_message"] = "codex did not update index.json status"
                    p["failed_at"] = ts_end
                    break
            save_index(index_file, fresh_index)
            update_top_index_status(task_dir_name, "error")
            sys.exit(1)

    index = load_index(index_file)
    index["completed_at"] = now_iso()
    save_index(index_file, index)
    update_top_index_status(task_dir_name, "completed")

    if _is_git_repo():
        git_run("add", "-A")
        if git_run("diff", "--cached", "--quiet").returncode != 0:
            msg = f"chore({task_name}): mark task completed"
            r = git_run("commit", "-m", msg)
            if r.returncode == 0:
                print(f"  + {msg}")

    print(f"\n{'=' * 60}")
    print(f"  Task {task_dir_name}: all phases completed!")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
