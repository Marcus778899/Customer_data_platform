"""派工單工具：manager 透過它開工。一張派工單 = 一個 GitHub issue = 一個分支 = 一個 worktree。

用法（內容從 stdin 傳入，格式依 templates/task-card.md）：
    task.py new <task_id>     < card.md    # 檢查格式 → 寫 tasks/<id>.md → 開 issue → 從 develop 開分支與 worktree
    task.py amend <task_id>   < note.md    # 追加內容（例：qa FAIL 的重現步驟），同步留言到 issue
    task.py cancel <task_id>  < 原因       # 作廢：關 PR 與 issue、刪 worktree 與分支；release.py 會略過作廢的單

沒有 origin remote 時只建本機分支，不開 issue。
exit code：0 成功；1 格式錯誤或狀態不對
"""

import argparse
import re
import sys
from datetime import datetime, timezone

from check_env import missing_settings
from _common import (
    BASE_BRANCH,
    BRANCH_TYPES,
    ROOT,
    TASKS_DIR,
    WORKTREES_DIR,
    FlowError,
    fail,
    has_remote,
    load_meta,
    number_from_url,
    require_on_base,
    run,
    save_meta,
)

REQUIRED_FIELDS = ("類型", "負責", "目標", "背景", "設計依據", "相依", "負責檔案", "禁區", "驗收測試", "驗收條件", "驗證指令", "回報檔")
PLACEHOLDERS = ("path/to/file.py", "path/to/acceptance/", "條件一", "一句話", "<task_id>", "feat / fix", "backend / frontend / architect / designer")
MAX_CARD_LINES = 40
MAX_BACKGROUND_LINES = 5
# worktree 需要、但不進 git 的東西，用 symlink 共用主目錄的
SHARED_PATHS = (".venv", "node_modules", ".env")
# 派工前必須已提交：worker 的 worktree 從 develop 開出，只看得到已提交的決策
DISPATCH_DOCS = ("BOARD.md", "docs/decisions.md")
FIELD_RE = re.compile(r"^- \*\*(.+?)\*\*[：:]?\s*(.*)$")
BACKTICK_RE = re.compile(r"`([^`]+)`")
# 這些類型要先由 qa 寫驗收測試
TDD_KINDS = ("feat", "fix")


def acceptance_paths(fields: dict[str, list[str]]) -> list[str]:
    """「驗收測試」欄位裡用反引號標出的路徑；寫「無」就是空的。"""
    return [p for line in fields.get("驗收測試", []) for p in BACKTICK_RE.findall(line)]


def card_acceptance_paths(task_id: str) -> list[str]:
    path = TASKS_DIR / f"{task_id}.md"
    if not path.exists():
        return []
    return acceptance_paths(parse_fields(path.read_text(encoding="utf-8")))


def parse_fields(body: str) -> dict[str, list[str]]:
    fields: dict[str, list[str]] = {}
    current = None
    for line in body.splitlines():
        match = FIELD_RE.match(line)
        if match:
            current = match.group(1)
            fields[current] = [match.group(2).strip()] if match.group(2).strip() else []
        elif line.startswith("#"):
            current = None
        elif current and line.strip():
            fields[current].append(line.strip())
    return fields


def check_card(task_id: str, body: str) -> list[str]:
    fields = parse_fields(body)
    problems = [f"缺少欄位「{f}」" for f in REQUIRED_FIELDS if f not in fields]
    problems += [f"還留著模板佔位字「{p}」" for p in PLACEHOLDERS if p in body]
    if f"reports/{task_id}.json" not in body:
        problems.append(f"回報檔必須是 reports/{task_id}.json")
    kind = " ".join(fields.get("類型", []))
    if kind and kind not in BRANCH_TYPES:
        problems.append(f"類型必須是 {'/'.join(BRANCH_TYPES)} 其中一個，實際是「{kind}」")
    if kind in TDD_KINDS and "驗收測試" in fields and not acceptance_paths(fields):
        problems.append(f"{kind} 單要列出「驗收測試」目錄（qa 先寫測試，worker 再實作）")
    lines = [line for line in body.splitlines() if line.strip()]
    if len(lines) > MAX_CARD_LINES:
        problems.append(f"派工單 {len(lines)} 行，上限 {MAX_CARD_LINES}；背景改用引用（見 docs/... §N），不要重講契約")
    if len(fields.get("背景", [])) > MAX_BACKGROUND_LINES:
        problems.append(f"背景超過 {MAX_BACKGROUND_LINES} 行")
    return problems


def start_worktree(task_id: str, kind: str) -> tuple[str, str]:
    if has_remote():
        run("git", "pull", "--ff-only", "origin", BASE_BRANCH)
    branch = f"{kind}/{task_id.lower()}"
    path = WORKTREES_DIR / task_id
    run("git", "worktree", "add", "-b", branch, str(path), BASE_BRANCH)
    for name in SHARED_PATHS:
        if (ROOT / name).exists() and not (path / name).exists():
            (path / name).symlink_to(ROOT / name)
    return branch, str(path.relative_to(ROOT))


def new_card(task_id: str, body: str) -> None:
    path = TASKS_DIR / f"{task_id}.md"
    if path.exists():
        raise FlowError(f"{path.relative_to(ROOT)} 已存在；要補充內容請用 amend")
    problems = check_card(task_id, body)
    if problems:
        raise FlowError("；".join(problems))
    require_on_base()
    pending_docs = run("git", "status", "--porcelain", "--", *DISPATCH_DOCS)
    if pending_docs:
        raise FlowError("布告欄或決策紀錄還沒提交，worker 從 develop 開分支會看不到：先 docs.py commit")
    missing = missing_settings()
    if missing:
        raise FlowError("甲方還沒填好 .env，不能開工；請甲方填入以下設定：\n" + "\n".join(missing))

    fields = parse_fields(body)
    kind = fields["類型"][0]
    title = fields["目標"][0]
    heading = f"# 派工單 {task_id}"
    if not body.lstrip().startswith(heading):
        body = f"{heading}\n\n{body.lstrip()}"
    TASKS_DIR.mkdir(exist_ok=True)
    path.write_text(body.rstrip() + "\n", encoding="utf-8")

    branch, worktree = start_worktree(task_id, kind)
    issue = None
    if has_remote():
        url = run("gh", "issue", "create", "--title", f"[{task_id}] {title}", "--body-file", str(path), "--label", kind)
        issue = number_from_url(url)
    else:
        print("警告：沒有 origin remote，未開 issue", file=sys.stderr)

    with path.open("a", encoding="utf-8") as f:
        f.write("\n## 執行資訊（task.py 產生）\n\n")
        if issue:
            f.write(f"- Issue：#{issue}\n")
        f.write(f"- 分支：`{branch}`（從 {BASE_BRANCH} 開出）\n")
        f.write(f"- 工作目錄：`{worktree}`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`\n")

    save_meta({
        "task_id": task_id,
        "kind": kind,
        "owner": fields["負責"][0],
        "title": title,
        "issue": issue,
        "branch": branch,
        "worktree": worktree,
        "pr": None,
        "qa": None,
        "review": None,
        "merged": False,
    })
    print(f"派工單：{path.relative_to(ROOT)}")
    print(f"Issue：#{issue}" if issue else "Issue：（無 remote）")
    print(f"分支：{branch}")
    print(f"工作目錄：{worktree}")


def amend_card(task_id: str, body: str) -> None:
    path = TASKS_DIR / f"{task_id}.md"
    if not path.exists():
        raise FlowError(f"{path.relative_to(ROOT)} 不存在；先用 new 建立")
    if not body.strip():
        raise FlowError("追加內容是空的")
    today = datetime.now(timezone.utc).date().isoformat()
    with path.open("a", encoding="utf-8") as f:
        f.write(f"\n## 追加 {today}\n\n{body.strip()}\n")
    issue = load_meta(task_id).get("issue")
    if issue:
        run("gh", "issue", "comment", str(issue), "--body", f"**派工單追加 {today}**\n\n{body.strip()}")
    print(path.relative_to(ROOT))


def cancel_card(task_id: str, reason: str) -> None:
    if not reason.strip():
        raise FlowError("原因是空的：寫明為什麼作廢（例：見 D-009）")
    require_on_base()
    meta = load_meta(task_id)
    if meta.get("merged"):
        raise FlowError(f"{task_id} 已經合併，不能作廢")
    note = f"**派工單作廢**\n\n{reason.strip()}"
    if meta.get("pr"):
        run("gh", "pr", "close", str(meta["pr"]), "--comment", note, check=False)
    if meta.get("issue"):
        run("gh", "issue", "close", str(meta["issue"]), "--reason", "not planned", "--comment", note, check=False)
    if (ROOT / meta["worktree"]).exists():
        run("git", "worktree", "remove", "--force", meta["worktree"])
    run("git", "branch", "-D", meta["branch"], check=False)
    if has_remote():
        run("git", "push", "origin", "--delete", meta["branch"], check=False)
    meta["cancelled"] = True
    save_meta(meta)
    print(f"{task_id} 已作廢：issue、PR 已關閉，worktree 與分支已刪除")


def main() -> None:
    parser = argparse.ArgumentParser(description="派工單工具")
    parser.add_argument("action", choices=("new", "amend", "cancel"))
    parser.add_argument("task_id")
    args = parser.parse_args()
    try:
        body = sys.stdin.read()
        if args.action == "new":
            new_card(args.task_id, body)
        elif args.action == "amend":
            amend_card(args.task_id, body)
        else:
            cancel_card(args.task_id, body)
    except FlowError as e:
        fail(str(e))


if __name__ == "__main__":
    main()
