"""腳本共用：路徑、git／gh 呼叫、派工單 meta。"""

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TASKS_DIR = ROOT / "tasks"
REPORTS_DIR = ROOT / "reports"
WORKTREES_DIR = ROOT / ".worktrees"
BASE_BRANCH = "develop"
RELEASE_BRANCH = "main"
BRANCH_TYPES = ("feat", "fix", "chore", "docs", "refactor", "test")


class FlowError(Exception):
    pass


def run(*cmd: str, cwd: Path = ROOT, check: bool = True) -> str:
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise FlowError(f"`{' '.join(cmd)}` 失敗：{detail}")
    return result.stdout.strip()


def run_status(*cmd: str, cwd: Path = ROOT) -> tuple[int, str]:
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return result.returncode, (result.stdout + result.stderr).strip()


def has_remote() -> bool:
    return "origin" in run("git", "remote", check=False).split()


def current_branch(cwd: Path = ROOT) -> str:
    return run("git", "rev-parse", "--abbrev-ref", "HEAD", cwd=cwd)


def require_on_base() -> None:
    branch = current_branch()
    if branch != BASE_BRANCH:
        raise FlowError(f"主目錄必須停在 {BASE_BRANCH}，目前在 {branch}；功能開發一律在 .worktrees/ 裡做")


def number_from_url(url: str) -> int:
    return int(url.strip().rstrip("/").rsplit("/", 1)[1])


def meta_path(task_id: str) -> Path:
    return TASKS_DIR / f"{task_id}.meta.json"


def load_meta(task_id: str) -> dict:
    path = meta_path(task_id)
    if not path.exists():
        raise FlowError(f"找不到 {path.relative_to(ROOT)}；先用 task.py new 建立派工單")
    return json.loads(path.read_text(encoding="utf-8"))


def save_meta(meta: dict) -> None:
    TASKS_DIR.mkdir(exist_ok=True)
    meta_path(meta["task_id"]).write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fail(message: str, code: int = 1) -> None:
    print(f"錯誤：{message}", file=sys.stderr)
    sys.exit(code)
