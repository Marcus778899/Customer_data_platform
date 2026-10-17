"""布告欄（BOARD.md）維護工具，manager 透過它更新，避免手改造成格式亂掉或超過行數上限。

用法：
    board.py scope "<本輪目標>" "<不做>"
    board.py task set <task_id> <status> [--owner <agent>] [--evidence "<驗收證據>"]
    board.py decision add <D-編號> "<一行摘要>"     # 細節寫在 docs/decisions.md
    board.py blocker add|clear "<卡點>"
    board.py check

status：todo / in-progress / qa / qa-fail / review / blocked / done（done = PR 已合併）
- done 必須附 --evidence
- qa-fail 會讓「嘗試」+1；達 MAX_ATTEMPTS 時 exit 3，提醒 manager 停下來上報甲方

exit code：0 成功；1 參數或格式錯誤；3 重試達上限
"""

import argparse
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

BOARD_PATH = Path(__file__).resolve().parent.parent / "BOARD.md"
MAX_LINES = 80
MAX_ATTEMPTS = 3
STATUSES = ("todo", "in-progress", "qa", "qa-fail", "review", "blocked", "done")
DECISION_ID_RE = re.compile(r"^D-\d{3}$")
SECTIONS = {
    "scope": "## 目標與範圍",
    "decisions": "## 已定案決策（細節見 docs/decisions.md；要改先問甲方）",
    "tasks": "## 任務狀態",
    "blockers": "## 卡住／待甲方決定",
    "notes": "## 注意事項（踩過的坑）",
}
EMPTY_ITEM = "- （無）"


class BoardError(Exception):
    pass


def _read() -> list[str]:
    return BOARD_PATH.read_text(encoding="utf-8").splitlines()


def _commit(lines: list[str]) -> None:
    """寫入前先檢查行數，超過上限就拒絕寫入。"""
    if len(lines) > MAX_LINES:
        raise BoardError(f"寫入後會有 {len(lines)} 行，超過上限 {MAX_LINES}；先清掉已完成的任務或過期的項目")
    BOARD_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _section_range(lines: list[str], key: str) -> tuple[int, int]:
    """回傳區塊內容的 [start, end)，不含標題行。"""
    heading = SECTIONS[key]
    if heading not in lines:
        raise BoardError(f"布告欄缺少區塊：{heading}")
    start = lines.index(heading) + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
    return start, end


def _table_rows(lines: list[str], key: str) -> list[int]:
    """回傳表格所有行（含表頭與分隔線）的索引。"""
    start, end = _section_range(lines, key)
    rows = [i for i in range(start, end) if lines[i].startswith("|")]
    if len(rows) < 2:
        raise BoardError(f"{SECTIONS[key]} 的表格缺表頭")
    return rows


def _split_row(line: str) -> list[str]:
    return [c.strip() for c in re.split(r"(?<!\\)\|", line)[1:-1]]


def _cell(text: str) -> str:
    return text.replace("\n", " ").replace("|", "\\|").strip()


def _row(cells: list[str]) -> str:
    return "| " + " | ".join(cells) + " |"


def set_task(task_id: str, status: str, owner: str | None, evidence: str | None) -> int:
    """更新「任務狀態」表，回傳目前嘗試次數。"""
    if status not in STATUSES:
        raise BoardError(f"未知狀態 {status!r}，可用：{', '.join(STATUSES)}")
    if status == "done" and not evidence:
        raise BoardError("status=done 必須附 --evidence（實際驗證指令與結果）")

    lines = _read()
    rows = _table_rows(lines, "tasks")
    existing = next((i for i in rows[2:] if _split_row(lines[i])[0] == task_id), None)

    if existing is None:
        if not owner:
            raise BoardError(f"新任務 {task_id} 必須給 --owner")
        attempts, old_evidence = 0, ""
    else:
        _, old_owner, _, old_attempts, old_evidence = _split_row(lines[existing])
        owner = owner or old_owner
        attempts = int(old_attempts or 0)

    if status == "qa-fail":
        attempts += 1

    new_row = _row([
        _cell(task_id),
        _cell(owner),
        status,
        str(attempts),
        _cell(evidence) if evidence else old_evidence,
    ])
    if existing is None:
        lines.insert(rows[-1] + 1, new_row)
    else:
        lines[existing] = new_row
    _commit(lines)
    return attempts


def set_scope(goal: str, exclude: str) -> None:
    lines = _read()
    start, end = _section_range(lines, "scope")
    lines[start:end] = ["", f"- 本輪：{_cell(goal)}", f"- 不做：{_cell(exclude)}", ""]
    _commit(lines)


def add_decision(decision_id: str, summary: str) -> None:
    if not DECISION_ID_RE.match(decision_id):
        raise BoardError(f"決策編號格式要是 D-001，實際是 {decision_id!r}")
    lines = _read()
    rows = _table_rows(lines, "decisions")
    if any(_split_row(lines[i])[1] == decision_id for i in rows[2:]):
        raise BoardError(f"{decision_id} 已在布告欄上")
    today = datetime.now(timezone.utc).date().isoformat()
    lines.insert(rows[-1] + 1, _row([today, decision_id, _cell(summary)]))
    _commit(lines)


def update_blocker(text: str, clear: bool) -> None:
    lines = _read()
    start, end = _section_range(lines, "blockers")
    items = [i for i in range(start, end) if lines[i].startswith("- ")]
    target = f"- {_cell(text)}"

    if clear:
        if target not in (lines[i] for i in items):
            raise BoardError(f"找不到卡點：{text}")
        lines.remove(target)
        if len(items) == 1:
            lines.insert(items[0], EMPTY_ITEM)
    elif items and lines[items[0]] == EMPTY_ITEM:
        lines[items[0]] = target
    elif items:
        lines.insert(items[-1] + 1, target)
    else:
        lines.insert(start + 1, target)
    _commit(lines)


def check() -> list[str]:
    problems: list[str] = []
    lines = _read()
    if len(lines) > MAX_LINES:
        problems.append(f"共 {len(lines)} 行，超過上限 {MAX_LINES}")
    for key, heading in SECTIONS.items():
        if heading not in lines:
            problems.append(f"缺少區塊：{heading}")
            continue
        if key != "tasks":
            continue
        for i in _table_rows(lines, "tasks")[2:]:
            cells = _split_row(lines[i])
            if len(cells) != 5:
                problems.append(f"第 {i + 1} 行欄位數不是 5：{lines[i]}")
                continue
            task_id, _, status, attempts, evidence = cells
            if status not in STATUSES:
                problems.append(f"{task_id}：未知狀態 {status!r}")
            if status == "done" and not evidence:
                problems.append(f"{task_id}：done 但沒有驗收證據")
            if status not in ("done", "blocked") and attempts.isdigit() and int(attempts) >= MAX_ATTEMPTS:
                problems.append(f"{task_id}：qa FAIL 已 {attempts} 次，應標 blocked 並上報甲方")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description="布告欄維護工具")
    sub = parser.add_subparsers(dest="area", required=True)

    task = sub.add_parser("task").add_subparsers(dest="action", required=True)
    task_set = task.add_parser("set")
    task_set.add_argument("task_id")
    task_set.add_argument("status", choices=STATUSES)
    task_set.add_argument("--owner")
    task_set.add_argument("--evidence")

    scope = sub.add_parser("scope")
    scope.add_argument("goal")
    scope.add_argument("exclude")

    decision = sub.add_parser("decision").add_subparsers(dest="action", required=True)
    decision_add = decision.add_parser("add")
    decision_add.add_argument("decision_id")
    decision_add.add_argument("summary")

    blocker = sub.add_parser("blocker")
    blocker.add_argument("action", choices=("add", "clear"))
    blocker.add_argument("text")

    sub.add_parser("check")

    args = parser.parse_args()
    exit_code = 0
    try:
        if args.area == "task":
            attempts = set_task(args.task_id, args.status, args.owner, args.evidence)
            if attempts >= MAX_ATTEMPTS and args.status == "qa-fail":
                print(f"{args.task_id} qa FAIL 已 {attempts} 次：停止重派，標 blocked 並上報甲方", file=sys.stderr)
                exit_code = 3
        elif args.area == "scope":
            set_scope(args.goal, args.exclude)
        elif args.area == "decision":
            add_decision(args.decision_id, args.summary)
        elif args.area == "blocker":
            update_blocker(args.text, args.action == "clear")
    except BoardError as e:
        print(f"錯誤：{e}", file=sys.stderr)
        sys.exit(1)

    problems = check()
    for p in problems:
        print(f"布告欄問題：{p}", file=sys.stderr)
    if problems and exit_code == 0:
        exit_code = 1
    if exit_code == 0:
        print("OK")
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
