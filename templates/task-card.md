# 派工單 <task_id>

<!-- 用 `python3 scripts/task.py new <task_id>` 建立（內容走 stdin）；格式不符、超過 40 行、背景超過 5 行會被拒絕 -->
<!-- 建立後 task.py 會自動附上 issue、分支、工作目錄 -->

- **類型**：feat / fix / chore / docs / refactor / test
- **負責**：backend / frontend / architect / designer
- **目標**：一句話
- **背景**：最多 5 行，只寫派工單以外看不到的事；契約與決策用引用（見 docs/contracts/x.md §2、D-003），不要重講
- **設計依據**：契約與設計文件路徑
- **相依**：要等哪些任務合併；沒有寫「無」（前端對契約 mock 寫「無，mock」）
- **負責檔案**：只能改這些（相對 repo 根目錄）
  - `path/to/file.py`
- **禁區**：不可改的檔案或行為
- **驗收測試**：qa 先寫、worker 不可改的目錄（見設計文件）；docs／chore 寫「無」
  - `path/to/acceptance/`
- **驗收條件**：
  - [ ] 條件一
- **驗證指令**：`make check`
- **回報檔**：`reports/<task_id>.json`
