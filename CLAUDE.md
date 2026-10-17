# CLAUDE.md（團隊共用規範）

所有 agent 啟動時都會讀這份。只放永遠成立的規則，越短越好；情境知識放 skills。

@BOARD.md

## 團隊規則

- 布告欄（BOARD.md）上的「已定案決策」不可推翻；與之矛盾時停下來回報 manager
- 只有 manager 可以改 BOARD.md（透過 `scripts/board.py`）、README.md、docs/decisions.md
- 每件工作都有派工單 `tasks/<task_id>.md`；只在派工單的工作目錄 `.worktrees/<task_id>` 修改「負責檔案」，唯一例外是回報檔 `reports/<task_id>.json`
- 分支：`main` ← `develop` ← `feat/`、`fix/`、`chore/`…；主目錄永遠停在 develop；不可直接 git push、merge、rebase、切換分支，一律透過 `scripts/`
- 需要甲方決定的事寫成 D-編號，集中在 `docs/decisions.md`；其他文件只引用編號
- 設定值（port、domain、token…）只放 `.env`；文件與程式只寫變數名稱；不可讀 `.env`
- 文件精簡：同一件事只寫一次，其他地方引用
- 「完成」必須附實際執行的驗證指令與輸出；沒跑過不算完成
- worker 回報格式依 `contracts/report.schema.json`

<!-- TODO(AE-13): 補上專案自己的規範（語言、測試指令、禁區）。專案既有 CLAUDE.md 時改為併入 -->
