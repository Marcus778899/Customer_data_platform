"""PR 工具：manager 透過它開 PR、記錄 qa／review 結果、合併。合併條件由程式把關，不靠 prompt。

用法：
    pr.py open <task_id>                          # 檢查回報 → push 分支 → 開 PR 到 develop（已有 PR 就只 push）
    pr.py record <task_id> qa pass|fail   < 內容   # 記錄 qa 結果並留言到 PR
    pr.py record <task_id> review approve|changes < 內容   # 記錄 architect code review（實作類任務）
    pr.py approve <task_id>...            < 內容   # 甲方核可（設計類任務，可一次多張）；settings 的 ask 讓它一定跳確認，按允許就是核可
    pr.py unblock <task_id>               < 內容   # qa FAIL 達上限後，甲方決定繼續；同樣一定跳確認
    pr.py merge <task_id>                         # qa PASS + review APPROVE（都對應目前的 commit）+ CI 綠 → merge commit 合併（保留分支歷史）、清掉 worktree 與本機分支；遠端分支保留供追蹤

qa／review 綁定 commit：之後再 push 新 commit，就要重新驗證與審查。
甲方關卡由程式把關（auto 模式下 prompt 擋不住）：
- 設計類任務（architect／designer 負責、類型 docs）的 review 只能用 approve，merge 會檢查；fix、chore 等走 architect review
- qa FAIL 達 MAX_QA_FAILS 次後 open 拒絕，要甲方 unblock
exit code：0 成功；1 條件不符或指令失敗
"""

import argparse
import json
import sys

from _common import (
    BASE_BRANCH,
    REPORTS_DIR,
    ROOT,
    FlowError,
    fail,
    load_meta,
    number_from_url,
    run,
    run_status,
    save_meta,
)
from validate_report import ReportError, check_consistency, check_schema, load_report

RESULTS = {"qa": ("pass", "fail"), "review": ("approve", "changes")}
LABELS = {"qa": "QA", "review": "Code review（architect）"}
GATED_OWNERS = ("architect", "designer")
GATED_KIND = "docs"
MAX_QA_FAILS = 3


def needs_client_approval(meta: dict) -> bool:
    """甲方只核可設計與決策：architect／designer 負責的 docs 類任務（設計、契約、畫面、技術棧）。"""
    return meta["owner"] in GATED_OWNERS and meta["kind"] == GATED_KIND


def head_sha(meta: dict) -> str:
    return run("git", "rev-parse", meta["branch"])


def open_pr(task_id: str) -> None:
    meta = load_meta(task_id)
    fails = meta.get("qa_fails", 0)
    if fails >= MAX_QA_FAILS:
        raise FlowError(
            f"{task_id} qa 已 FAIL {fails} 次：停下來，把卡點與選項開成 D-編號問甲方；"
            f"甲方決定繼續後執行 pr.py unblock {task_id}"
        )
    worktree = ROOT / meta["worktree"]
    if run("git", "status", "--porcelain", cwd=worktree):
        raise FlowError(f"{meta['worktree']} 還有沒 commit 的修改，請 worker 先 commit")
    ahead = int(run("git", "rev-list", "--count", f"{BASE_BRANCH}..{meta['branch']}"))
    if ahead == 0:
        raise FlowError(f"{meta['branch']} 沒有任何新 commit")

    report_path = REPORTS_DIR / f"{task_id}.json"
    try:
        report = load_report(report_path)
        errors = check_schema(report) or check_consistency(report, report_path)
    except ReportError as e:
        errors = [str(e)]
    if errors:
        raise FlowError("回報不合格：" + "；".join(errors))
    if report["status"] != "done":
        raise FlowError(f"回報狀態是 {report['status']}，不是 done")

    run("git", "push", "-u", "origin", meta["branch"], cwd=worktree)
    if meta.get("pr"):
        print(f"已更新 PR #{meta['pr']}（新 commit 需要重新 qa 與 review）")
        return

    verification = report["verification"]
    closes = f"Closes #{meta['issue']}\n\n" if meta.get("issue") else ""
    body = (
        f"{closes}派工單：`tasks/{task_id}.md`（負責：{meta['owner']}）\n\n"
        f"Worker 自驗：`{verification['command']}` → exit {verification['exit_code']}\n\n"
        "變更檔案：\n" + "\n".join(f"- `{f}`" for f in report["files_changed"])
    )
    url = run(
        "gh", "pr", "create", "--base", BASE_BRANCH, "--head", meta["branch"],
        "--title", f"{meta['kind']}({task_id}): {meta['title']}", "--body", body,
    )
    meta["pr"] = number_from_url(url)
    save_meta(meta)
    print(f"PR #{meta['pr']}：{url}")


def record(task_id: str, kind: str, result: str, body: str) -> None:
    if result not in RESULTS[kind]:
        raise FlowError(f"{kind} 結果只能是 {'/'.join(RESULTS[kind])}")
    if not body.strip():
        raise FlowError("內容是空的：qa 要附實際執行輸出，review 要附檢查結果")
    meta = load_meta(task_id)
    if kind == "review" and needs_client_approval(meta):
        raise FlowError(f"{task_id} 是設計類任務，要甲方核可：pr.py approve {task_id}")
    sha = head_sha(meta)
    meta[kind] = {"result": result, "sha": sha, "by": "architect" if kind == "review" else "qa"}
    if kind == "qa" and result == "fail":
        meta["qa_fails"] = meta.get("qa_fails", 0) + 1
    save_meta(meta)
    comment(meta, f"**{LABELS[kind]}：{result.upper()}**（commit `{sha[:7]}`）\n\n{body.strip()}")
    print(f"{task_id} {kind}={result} @ {sha[:7]}")
    if meta.get("qa_fails", 0) >= MAX_QA_FAILS:
        print(f"{task_id} qa 已 FAIL {meta['qa_fails']} 次：不可再重派，問甲方", file=sys.stderr)


def approve(task_ids: list[str], body: str) -> None:
    """甲方核可設計類任務（可一次多張，只跳一次確認）。settings.json 把這個指令放在 ask，甲方按允許就是核可。"""
    if not body.strip():
        raise FlowError("內容是空的：寫明甲方核可了什麼（例：D-001～D-004 照建議）")
    metas = [load_meta(task_id) for task_id in task_ids]
    for meta in metas:
        sha = head_sha(meta)
        meta["review"] = {"result": "approve", "sha": sha, "by": "甲方"}
        save_meta(meta)
        comment(meta, f"**甲方核可**（commit `{sha[:7]}`）\n\n{body.strip()}")
        print(f"{meta['task_id']} 甲方核可 @ {sha[:7]}")


def unblock(task_id: str, body: str) -> None:
    """qa FAIL 達上限後，甲方決定繼續。同樣放在 ask。"""
    if not body.strip():
        raise FlowError("內容是空的：寫明甲方的決定（例：見 D-007，改用方案 B 再試）")
    meta = load_meta(task_id)
    meta["qa_fails"] = 0
    save_meta(meta)
    comment(meta, f"**甲方決定繼續**（qa 失敗次數歸零）\n\n{body.strip()}")
    print(f"{task_id} 已解除封鎖")


def comment(meta: dict, body: str) -> None:
    if meta.get("pr"):
        run("gh", "pr", "comment", str(meta["pr"]), "--body", body)


def check_ci(pr: int) -> None:
    code, output = run_status("gh", "pr", "checks", str(pr))
    if "no checks reported" in output:
        raise FlowError(f"PR #{pr} 還沒有 CI 結果：剛 push 的話 CI 可能還沒開始，用 `gh pr checks {pr} --watch` 等它跑完再合併；一直沒有就檢查 .github/workflows")
    if code == 8:
        raise FlowError(f"PR #{pr} 的 CI 還在跑，稍後再合併")
    if code != 0:
        raise FlowError(f"PR #{pr} 的 CI 沒過：\n{output}")


def merge(task_id: str) -> None:
    meta = load_meta(task_id)
    if not meta.get("pr"):
        raise FlowError("還沒開 PR")
    sha = head_sha(meta)
    remote_sha = json.loads(run("gh", "pr", "view", str(meta["pr"]), "--json", "headRefOid"))["headRefOid"]
    if remote_sha != sha:
        raise FlowError("本機分支與 PR 上的 commit 不一致：有沒推上去的 commit 就先 pr.py open；剛 push 的話 GitHub 要幾秒才更新，稍後再試")
    for kind, ok in (("qa", "pass"), ("review", "approve")):
        state = meta.get(kind)
        if not state or state["result"] != ok:
            raise FlowError(f"{kind} 還不是 {ok}")
        if state["sha"] != sha:
            raise FlowError(f"{kind} 驗的是舊 commit {state['sha'][:7]}，目前是 {sha[:7]}，要重新 {kind}")
    if needs_client_approval(meta) and meta["review"].get("by") != "甲方":
        raise FlowError(f"設計類任務要甲方核可：pr.py approve {task_id}")
    check_ci(meta["pr"])

    # merge commit：分支上的 commit 留在歷史裡，Git Graph 看得到每張單的分支；遠端分支不刪，方便追蹤
    run("gh", "pr", "merge", str(meta["pr"]), "--merge")
    run("git", "worktree", "remove", meta["worktree"])
    run("git", "branch", "-D", meta["branch"])
    run("git", "pull", "--ff-only", "origin", BASE_BRANCH)
    meta["merged"] = True
    save_meta(meta)
    print(f"PR #{meta['pr']} 已合併到 {BASE_BRANCH}，worktree 與本機分支已清除（遠端分支保留）")


def main() -> None:
    parser = argparse.ArgumentParser(description="PR 工具")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("open").add_argument("task_id")
    rec = sub.add_parser("record")
    rec.add_argument("task_id")
    rec.add_argument("kind", choices=tuple(RESULTS))
    rec.add_argument("result")
    sub.add_parser("approve").add_argument("task_ids", nargs="+")
    sub.add_parser("unblock").add_argument("task_id")
    sub.add_parser("merge").add_argument("task_id")
    args = parser.parse_args()
    try:
        if args.action == "open":
            open_pr(args.task_id)
        elif args.action == "record":
            record(args.task_id, args.kind, args.result, sys.stdin.read())
        elif args.action == "approve":
            approve(args.task_ids, sys.stdin.read())
        elif args.action == "unblock":
            unblock(args.task_id, sys.stdin.read())
        else:
            merge(args.task_id)
    except FlowError as e:
        fail(str(e))


if __name__ == "__main__":
    main()
