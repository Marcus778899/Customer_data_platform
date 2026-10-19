"""發版工具：一個階段完成後，develop 合回 main 並打 tag。

用法：
    release.py open <vX.Y.Z>     # 檢查：任務都已合併、UAT 甲方驗收清單全勾且寫了 PASS → 開 develop→main 的 PR
    release.py finish <vX.Y.Z>   # CI 綠才合併（merge commit）→ 在 main 打 tag → 建 GitHub release
                                 # → 封存布告欄與派工單到 tasks/<vX.Y.Z>（board.py archive）並提交到 develop

main 更新後，.github/workflows/sync_branch.yml 會把 main 同步回 develop。
exit code：0 成功；1 條件不符或指令失敗
"""

import argparse
import json
import re

from _common import (
    BASE_BRANCH,
    RELEASE_BRANCH,
    ROOT,
    TASKS_DIR,
    FlowError,
    fail,
    number_from_url,
    require_on_base,
    run,
)
from board import BoardError, archive
from docs import commit as commit_docs
from pr import check_ci

VERSION_RE = re.compile(r"^v\d+\.\d+\.\d+$")
UAT_DIR = ROOT / "docs" / "uat"
SIGNOFF_HEADING = "## 甲方驗收清單"
SIGNOFF_PASS = "甲方驗收：PASS"


def release_meta_path(version: str):
    return TASKS_DIR / f"release-{version}.json"


def check_uat(version: str) -> None:
    path = UAT_DIR / f"{version}.md"
    if not path.exists():
        raise FlowError(f"找不到 {path.relative_to(ROOT)}；先派 uat 做使用者測試")
    text = path.read_text(encoding="utf-8")
    if SIGNOFF_HEADING not in text:
        raise FlowError(f"{path.name} 缺少「{SIGNOFF_HEADING}」")
    signoff = text.split(SIGNOFF_HEADING, 1)[1]
    unchecked = [line.strip() for line in signoff.splitlines() if line.strip().startswith("- [ ]")]
    if unchecked:
        raise FlowError("甲方驗收清單還有沒勾的項目：\n" + "\n".join(unchecked))
    if SIGNOFF_PASS not in signoff:
        raise FlowError(f"甲方尚未在 {path.name} 寫下「{SIGNOFF_PASS}」")


def check_tasks_merged() -> None:
    pending = []
    for path in TASKS_DIR.glob("*.meta.json"):
        meta = json.loads(path.read_text(encoding="utf-8"))
        if not meta.get("merged") and not meta.get("cancelled"):
            pending.append(meta["task_id"])
    if pending:
        raise FlowError(f"還有沒合併的任務：{', '.join(sorted(pending))}（不做的用 task.py cancel 作廢）")


def open_release(version: str) -> None:
    if not VERSION_RE.match(version):
        raise FlowError("版本格式要是 vX.Y.Z")
    if run("git", "ls-remote", "--tags", "origin", version):
        raise FlowError(f"tag {version} 已存在")
    require_on_base()
    if run("git", "status", "--porcelain"):
        raise FlowError("主目錄有沒提交的修改；文件先用 docs.py commit，其他走派工單")
    check_tasks_merged()
    check_uat(version)
    run("git", "pull", "--ff-only", "origin", BASE_BRANCH)
    body = f"階段發版 {version}\n\n使用者測試與甲方驗收：`docs/uat/{version}.md`"
    url = run("gh", "pr", "create", "--base", RELEASE_BRANCH, "--head", BASE_BRANCH, "--title", f"release: {version}", "--body", body)
    pr = number_from_url(url)
    release_meta_path(version).write_text(json.dumps({"version": version, "pr": pr}) + "\n", encoding="utf-8")
    print(f"發版 PR #{pr}：{url}")


def finish_release(version: str) -> None:
    path = release_meta_path(version)
    if not path.exists():
        raise FlowError("先執行 release.py open")
    pr = json.loads(path.read_text(encoding="utf-8"))["pr"]
    check_ci(pr)
    run("gh", "pr", "merge", str(pr), "--merge")
    run("git", "fetch", "origin")
    run("git", "tag", "-a", version, f"origin/{RELEASE_BRANCH}", "-m", f"release {version}")
    run("git", "push", "origin", version)
    run("gh", "release", "create", version, "--verify-tag", "--generate-notes", "--title", version)
    print(f"{version} 已合併到 {RELEASE_BRANCH} 並打 tag；develop 會由 sync_branch workflow 同步")
    try:
        archive(version)
        commit_docs(f"封存 {version} 布告欄與派工單")
    except (BoardError, FlowError) as e:
        raise FlowError(f"發版已完成，但封存沒做完：{e}\n處理後執行 board.py archive {version}（已封存就略過）與 docs.py commit") from e


def main() -> None:
    parser = argparse.ArgumentParser(description="發版工具")
    parser.add_argument("action", choices=("open", "finish"))
    parser.add_argument("version")
    args = parser.parse_args()
    try:
        if args.action == "open":
            open_release(args.version)
        else:
            finish_release(args.version)
    except FlowError as e:
        fail(str(e))


if __name__ == "__main__":
    main()
