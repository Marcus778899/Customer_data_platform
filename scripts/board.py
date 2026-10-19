"""布告欄（BOARD.md）維護工具，manager 透過它更新，避免手改造成格式亂掉（D-054：不設行數上限）。

用法：
    board.py scope "<本輪目標>" "<不做>"
    board.py task set <task_id> <status> [--owner <agent>] [--evidence "<驗收證據>"]
    board.py task archive <vX.Y.Z> <代稱>           # BOARD.md 快照到 tasks/<版本>/<代稱>.md，pr（無則 issue）小於
                                                    # release-<版本>.json 的 pr 且已合併／作廢的派工單連同 release 檔
                                                    # 搬進 tasks/<版本>/，再移除所有 done 的列（D-039、D-040）；
                                                    # 沒有 release 檔就拒絕；失敗會反向還原
    board.py decision add <D-編號> "<一行摘要>" [--version vX.Y.Z]   # 細節寫在 docs/decisions.md；
                                                    # 未指定版本取「目標與範圍」本輪的版本號
    board.py decision backfill-version              # 一次性補「版本」欄：D-001～D-032 標 v0.1.0，D-033 起標 v0.2.0
    board.py blocker add|clear "<卡點>"
    board.py check

status：todo / in-progress / qa / qa-fail / review / blocked / done（done = PR 已合併）
- done 必須附 --evidence
- qa-fail 會讓「嘗試」+1；達 MAX_ATTEMPTS 時 exit 3，提醒 manager 停下來上報甲方

代稱：小寫英數與 -（例：engine-ignition）。若舊的 tasks/job_history/<版本>.md 存在，
改用它當快照（搬到 tasks/<版本>/<代稱>.md），不改 BOARD.md；job_history/ 空了就刪除。

環境變數（測試用）：BOARD_FILE 指定 BOARD.md 路徑；TASKS_DIR 指定 tasks 目錄
（預設為專案根目錄的 BOARD.md 與 tasks/）

exit code：0 成功；1 參數或格式錯誤；3 重試達上限
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

BOARD_PATH = Path(os.environ.get("BOARD_FILE") or Path(__file__).resolve().parent.parent / "BOARD.md")
TASKS_PATH = Path(os.environ.get("TASKS_DIR") or Path(__file__).resolve().parent.parent / "tasks")
VERSION_RE = re.compile(r"^v\d+\.\d+\.\d+$")
ALIAS_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
LEGACY_LAST_V010 = 32  # D-032 以前屬 v0.1.0，其後屬 v0.2.0（一次性補欄用）
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
    """寫回 BOARD.md（D-054：不設行數上限）。"""
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


def _release_pr(version: str) -> int:
    """讀 release-<版本>.json 的 pr；檔案不存在或沒有 pr 就拒絕（不改任何檔）。"""
    release = TASKS_PATH / f"release-{version}.json"
    if not release.exists():
        raise BoardError(f"找不到 {release}；沒有發版紀錄無法判斷哪些派工單屬於 {version}")
    try:
        pr = json.loads(release.read_text(encoding="utf-8")).get("pr")
    except (OSError, ValueError) as e:
        raise BoardError(f"無法讀取 {release.name}：{e}") from e
    if not isinstance(pr, int):
        raise BoardError(f"{release.name} 沒有數字的 pr 欄位")
    return pr


def _archivable_files(version: str, release_pr: int) -> list[Path]:
    """tasks/ 根目錄中 meta 為已合併或已作廢、且 pr（無 pr 用 issue）小於發版 PR 的 <id>.md、<id>.meta.json，加上 release-<版本>.json。"""
    files: list[Path] = []
    for meta_file in sorted(TASKS_PATH.glob("*.meta.json")):
        try:
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise BoardError(f"無法讀取 {meta_file.name}：{e}") from e
        if not (meta.get("merged") or meta.get("cancelled")):
            continue
        number = meta.get("pr") or meta.get("issue")
        if not isinstance(number, int) or number >= release_pr:
            continue  # 發版後才合併（或無編號可比）：留在根目錄給下一版
        card = meta_file.with_name(meta_file.name[: -len(".meta.json")] + ".md")
        files.extend(f for f in (card, meta_file) if f.exists())
    files.append(TASKS_PATH / f"release-{version}.json")
    return files


def archive_done(version: str, alias: str) -> tuple[list[str], list[str]]:
    """快照到 tasks/<版本>/<代稱>.md、搬移該版已合併／作廢的派工單，再移除 done 的列。

    回傳（被移除的任務 ID，搬移的檔名）。所有檢查在寫檔前完成；任何一步失敗都反向還原。
    """
    if not VERSION_RE.match(version):
        raise BoardError(f"版本格式要是 v0.1.0，實際是 {version!r}")
    if not ALIAS_RE.match(alias):
        raise BoardError(f"代稱只能用小寫英數與 -（例：engine-ignition），實際是 {alias!r}")
    release_pr = _release_pr(version)
    dest_dir = TASKS_PATH / version
    snapshot = dest_dir / f"{alias}.md"
    legacy_dir = TASKS_PATH / "job_history"
    legacy = legacy_dir / f"{version}.md"
    files = _archivable_files(version, release_pr)

    # 撞名檢查（不分大小寫，含快照檔名與搬入檔之間），寫任何檔之前完成
    existing = {p.name.lower() for p in dest_dir.iterdir()} if dest_dir.is_dir() else set()
    targets = [snapshot.name] + [f.name for f in files]
    seen: set[str] = set()
    clash = []
    for name in targets:
        key = name.lower()
        if key in existing or key in seen:
            clash.append(name)
        seen.add(key)
    if clash:
        raise BoardError(f"{dest_dir} 已有同名檔案（不分大小寫），拒絕覆寫：{', '.join(clash)}")

    original = BOARD_PATH.read_text(encoding="utf-8")
    lines = original.splitlines()
    rows = _table_rows(lines, "tasks")
    done = [i for i in rows[2:] if _split_row(lines[i])[2] == "done"]
    ids = [_split_row(lines[i])[0] for i in done]
    migrate = legacy.exists()
    if migrate:
        ids = []  # 遷移：快照早已存在，不改 BOARD.md
    elif not done:
        return ids, []
    else:
        for i in reversed(done):
            del lines[i]

    moved: list[str] = []
    undo: list = []  # 反向還原動作，後進先出
    try:
        if not dest_dir.exists():
            dest_dir.mkdir(parents=True)
            undo.append(dest_dir.rmdir)
        if migrate:
            legacy.rename(snapshot)
            undo.append(lambda: snapshot.rename(legacy))
            moved.append(f"job_history/{legacy.name} -> {version}/{snapshot.name}")
        else:
            today = datetime.now(timezone.utc).date().isoformat()
            with open(snapshot, "x", encoding="utf-8") as f:  # x：不覆寫
                undo.append(snapshot.unlink)
                f.write(f"<!-- 封存 {version}，{today} -->\n{original}")
            moved.append(f"BOARD.md -> {version}/{snapshot.name}（快照）")
        for f in files:
            target = dest_dir / f.name
            f.rename(target)
            undo.append(lambda f=f, target=target: target.rename(f))
            moved.append(f"{f.name} -> {version}/")
        if not migrate:
            _commit(lines)
            undo.append(lambda: BOARD_PATH.write_text(original, encoding="utf-8"))
        if migrate and legacy_dir.is_dir() and not any(legacy_dir.iterdir()):
            legacy_dir.rmdir()  # 全部搬完才刪
    except OSError as e:
        failed = []
        for action in reversed(undo):
            try:
                action()
            except OSError as e2:
                failed.append(str(e2))
        tail = "；還原也失敗，請手動檢查：" + "; ".join(failed) if failed else "；已還原"
        raise BoardError(f"歸檔失敗：{e}{tail}") from e
    return ids, moved


def set_scope(goal: str, exclude: str) -> None:
    lines = _read()
    start, end = _section_range(lines, "scope")
    lines[start:end] = ["", f"- 本輪：{_cell(goal)}", f"- 不做：{_cell(exclude)}", ""]
    _commit(lines)


def _current_version(lines: list[str]) -> str:
    """「目標與範圍」的「本輪：」第一個版本號。"""
    start, end = _section_range(lines, "scope")
    for i in range(start, end):
        if lines[i].startswith("- 本輪"):
            m = re.search(r"v\d+\.\d+\.\d+", lines[i])
            if m:
                return m.group(0)
    raise BoardError("「目標與範圍」的「本輪」找不到版本號（vX.Y.Z），請用 --version 指定")


def _decision_header(lines: list[str]) -> list[str]:
    rows = _table_rows(lines, "decisions")
    return _split_row(lines[rows[0]])


def add_decision(decision_id: str, summary: str, version: str | None = None) -> None:
    if not DECISION_ID_RE.match(decision_id):
        raise BoardError(f"決策編號格式要是 D-001，實際是 {decision_id!r}")
    if version is not None and not VERSION_RE.match(version):
        raise BoardError(f"版本格式要是 v0.1.0，實際是 {version!r}")
    lines = _read()
    rows = _table_rows(lines, "decisions")
    if "版本" not in _decision_header(lines):
        raise BoardError("決策表還沒有「版本」欄；先跑 board.py decision backfill-version")
    if any(_split_row(lines[i])[1] == decision_id for i in rows[2:]):
        raise BoardError(f"{decision_id} 已在布告欄上")
    version = version or _current_version(lines)
    today = datetime.now(timezone.utc).date().isoformat()
    lines.insert(rows[-1] + 1, _row([today, decision_id, version, _cell(summary)]))
    _commit(lines)


def backfill_decision_version() -> int:
    """一次性：決策表加「版本」欄（日期、編號之後）。D-001～D-032 標 v0.1.0，D-033 起標 v0.2.0。回傳補了幾列。"""
    lines = _read()
    rows = _table_rows(lines, "decisions")
    if "版本" in _split_row(lines[rows[0]]):
        raise BoardError("決策表已有「版本」欄，不需補欄")
    new_rows = {rows[0]: _row(["日期", "編號", "版本", "決策"]), rows[1]: "|---|---|---|---|"}
    for i in rows[2:]:
        cells = _split_row(lines[i])
        if len(cells) != 3 or not DECISION_ID_RE.match(cells[1]):
            raise BoardError(f"第 {i + 1} 行不是「日期｜D-編號｜決策」：{lines[i]}")
        number = int(cells[1][2:])
        new_rows[i] = _row([cells[0], cells[1], "v0.1.0" if number <= LEGACY_LAST_V010 else "v0.2.0", cells[2]])
    for i, text in new_rows.items():
        lines[i] = text
    _commit(lines)
    return len(rows) - 2


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

    archive = task.add_parser("archive")
    archive.add_argument("version")
    archive.add_argument("alias")

    scope = sub.add_parser("scope")
    scope.add_argument("goal")
    scope.add_argument("exclude")

    decision = sub.add_parser("decision").add_subparsers(dest="action", required=True)
    decision_add = decision.add_parser("add")
    decision_add.add_argument("decision_id")
    decision_add.add_argument("summary")
    decision_add.add_argument("--version")
    decision.add_parser("backfill-version")

    blocker = sub.add_parser("blocker")
    blocker.add_argument("action", choices=("add", "clear"))
    blocker.add_argument("text")

    sub.add_parser("check")

    args = parser.parse_args()
    exit_code = 0
    try:
        if args.area == "task" and args.action == "archive":
            ids, moved = archive_done(args.version, args.alias)
            for m in moved:
                print(f"搬移：{m}")
            if ids:
                print(f"已移除 {len(ids)} 張 done 任務：{', '.join(ids)}")
            elif not moved:
                print("沒有 done 的任務可封存（未寫快照）")
        elif args.area == "task":
            attempts = set_task(args.task_id, args.status, args.owner, args.evidence)
            if attempts >= MAX_ATTEMPTS and args.status == "qa-fail":
                print(f"{args.task_id} qa FAIL 已 {attempts} 次：停止重派，標 blocked 並上報甲方", file=sys.stderr)
                exit_code = 3
        elif args.area == "scope":
            set_scope(args.goal, args.exclude)
        elif args.area == "decision":
            if args.action == "backfill-version":
                print(f"已補「版本」欄：{backfill_decision_version()} 列")
            else:
                add_decision(args.decision_id, args.summary, args.version)
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
