"""文件提交：布告欄、README、決策紀錄、UAT 報告、研究摘要不走 PR，由 manager 直接 commit 到 develop。

用法：
    docs.py commit "<訊息>"     # 只提交 DOC_PATHS 內的修改；其他修改不提交也不擋，只列出來

程式碼的修改一律走派工單 → PR，不可用這支。
"""

import argparse

from _common import BASE_BRANCH, FlowError, fail, has_remote, require_on_base, run

DOC_PATHS = ("BOARD.md", "README.md", "docs/decisions.md", "docs/uat/", "docs/research/")


def changed_paths() -> list[str]:
    tracked = run("git", "diff", "HEAD", "--name-only").splitlines()
    untracked = run("git", "ls-files", "--others", "--exclude-standard").splitlines()
    return sorted(set(tracked + untracked))


def commit(message: str) -> None:
    require_on_base()
    changed = changed_paths()
    paths = [p for p in changed if any(p == d or (d.endswith("/") and p.startswith(d)) for d in DOC_PATHS)]
    others = [p for p in changed if p not in paths]
    if not paths:
        raise FlowError("沒有任何文件修改")
    if has_remote():
        run("git", "pull", "--ff-only", "origin", BASE_BRANCH)
    run("git", "add", "--", *paths)
    message = message.removeprefix("docs:").strip()
    run("git", "commit", "-m", f"docs: {message}", "--", *paths)
    if has_remote():
        run("git", "push", "origin", BASE_BRANCH)
    print(f"已提交到 {BASE_BRANCH}：{'、'.join(paths)}")
    if others:
        print("主目錄另有非文件修改（沒提交，不影響流程，不用找甲方處理）：" + "、".join(others))


def main() -> None:
    parser = argparse.ArgumentParser(description="文件提交")
    parser.add_argument("action", choices=("commit",))
    parser.add_argument("message")
    args = parser.parse_args()
    try:
        commit(args.message)
    except FlowError as e:
        fail(str(e))


if __name__ == "__main__":
    main()
