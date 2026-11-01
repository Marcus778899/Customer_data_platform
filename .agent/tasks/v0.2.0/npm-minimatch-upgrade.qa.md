PASS a089d9a
驗證於暫存目錄 $TMPDIR/qa1/{old,new}（git archive develop／PR HEAD，各自 npm ci，未動主目錄 node_modules）。
- 變更檔：package.json（overrides minimatch ^11.0.1）、package-lock.json；worktree 乾淨；只改負責檔案。
- npm ci：兩邊成功。npm ls minimatch：old 全為 10.2.6；new eslint、@eslint/config-array、typescript-estree 皆 11.0.3（deduped）。
- eslint . + tsc --noEmit：old/new 皆 exit 0；eslint -f json 實際檢查檔案數 old=49、new=49，0 error 0 warn（glob 比對無檔案被略過）。
- 反例：new 內加 `var x=1; debugger` 的檔案，eslint 仍報 3 errors（含 no-debugger），規則仍有效。
- vitest：old/new 皆 16 files、196 tests passed。
- PR #136 CI：Changes pass、Check pass（3m16s）、Security pass（50s）。
- 備註：eslint 10.14／typescript-eslint 8.72.1 仍要求 ^10.2.6，override 為過渡；合併後主目錄需 npm ci。make check 未在 worktree 跑（node_modules 為主目錄 symlink），以暫存目錄等效驗證，CI Check 已綠。
