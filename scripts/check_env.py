"""檢查甲方要填的設定（.env）是否都填了。只印變數名稱，絕不印出值。

.env.example 是設定清單（可 commit）：每行 `KEY=預設值`，上一行的 `#` 註解是說明。
值為空或含 CHANGE_ME 的，代表一定要甲方決定。

用法：
    check_env.py      # .env 不存在就從 .env.example 複製一份；列出還沒填的變數

exit code：0 全部填好；1 還有沒填的（manager 要停下來請甲方填）
"""

import shutil
import sys

from _common import ROOT

EXAMPLE = ROOT / ".env.example"
ENV = ROOT / ".env"
PLACEHOLDER = "CHANGE_ME"


def parse(path) -> dict[str, tuple[str, str]]:
    """回傳 {KEY: (值, 說明)}。"""
    entries: dict[str, tuple[str, str]] = {}
    comment = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("#"):
            comment = line.lstrip("# ")
        elif "=" in line:
            key, value = line.split("=", 1)
            entries[key.strip()] = (value.strip().strip('"').strip("'"), comment)
            comment = ""
        else:
            comment = ""
    return entries


def missing_settings() -> list[str]:
    """回傳還沒填的變數（含說明），不會建立或修改任何檔案。"""
    if not EXAMPLE.exists():
        return []
    actual = parse(ENV) if ENV.exists() else {}
    missing = []
    for key, (_, comment) in parse(EXAMPLE).items():
        value = actual.get(key, ("", ""))[0]
        if not value or PLACEHOLDER in value:
            missing.append(f"- {key}：{comment}" if comment else f"- {key}")
    return missing


def main() -> None:
    if not EXAMPLE.exists():
        print("沒有 .env.example，這個專案不需要設定")
        return
    if not ENV.exists():
        shutil.copy(EXAMPLE, ENV)
        print("已從 .env.example 建立 .env")
    example = parse(EXAMPLE)
    missing = missing_settings()
    if missing:
        print(f"請甲方在 {ENV.relative_to(ROOT)} 填入以下設定，填完再繼續：", file=sys.stderr)
        print("\n".join(missing), file=sys.stderr)
        sys.exit(1)
    print(f"設定齊全（{len(example)} 項）")


if __name__ == "__main__":
    main()
