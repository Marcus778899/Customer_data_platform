# LICENSE 範本調查（D-008／D-010）

查詢日期：2026-10-18

**結論**
1. 未找到可信來源的 All Rights Reserved／Proprietary LICENSE 現成全文範本，故不提供全文。查不到。
2. 最接近的可信來源是 choosealicense.com 的 no-permission 頁（說明「無授權＝預設專有著作權」），但該頁不含可直接複製的全文。
3. 事實：public repo 上他人仍可檢視與 fork（GitHub ToS D.5），但此不等於可使用、修改、散布。

**1. 範本**
- 查不到。搜尋 "proprietary license template All Rights Reserved" 等關鍵字，結果多為 GPL 鏡像或商用 freeware 條款頁，均非可直接採用的「保留所有權利」全文。
- 建議：另找來源（如律師或法律科技機構提供的範本）後再交甲方／D-010 確認著作權人名稱。

**2. public repo 加 All Rights Reserved 後，他人仍能做的事（事實）**
| 行為 | 來源與原文 |
|---|---|
| 檢視與 fork | GitHub ToS D.5 "License Grant to Other Users"：「By setting your repositories to be viewed publicly, you agree to allow others to view and "fork" your repositories」（生效日 2026-04-27） https://docs.github.com/en/site-policy/github-terms/github-terms-of-service |
| 檢視或 fork 不代表可用 | choosealicense no-permission：viewing or forking「does not imply that you are permitted to use, modify, or share the software for any purpose」 https://choosealicense.com/no-permission/ |
| 其他人不得複製／散布／衍生 | GitHub licensing 文件（經 WebFetch 摘要）：無授權時「the default copyright laws apply, meaning that you retain all rights to your source code」、「no one may reproduce, distribute, or create derivative works from your work」 https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository |
| 無 LICENSE 不代表放棄權利 | choosealicense 同頁：作品預設「under exclusive copyright」（頁面無更新日期） |

- 注意：GitHub 文件與 choosealicense 皆稱 fork 是平台行為，非授權；轉為 private 後既有 fork 不會消失（來源為舊版 Enterprise 文件搜尋摘要，現行頁面未確認，查不到現行原文）。
- 建議：LICENSE 文字宜明寫「不授予 fork 以外之任何權利」之類條款，由甲方與律師決定措辭（非本調查決策）。

**3. 來源與查詢日期（均 2026-10-18）**
- https://choosealicense.com/no-permission/（頁面無日期）
- https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository
- https://docs.github.com/en/site-policy/github-terms/github-terms-of-service（生效日 2026-04-27）
- 未取得可用範本來源；無法提供 `<COPYRIGHT HOLDER>`／`<YEAR>` 版全文。
