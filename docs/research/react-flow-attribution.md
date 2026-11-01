# React Flow（@xyflow/react 12.x）attribution 規定查證

結論（事實，非決策）：
1. 授權為 MIT；官方原文稱「We can't legally require you to keep the attribution」，即法律上無法要求保留 attribution。`hideAttribution` 是 `proOptions` 的布林選項，可隱藏。
2. 官方對隱藏的要求是「請訂閱」，屬請求而非授權條款：「If you hide the attribution, please support our work with a subscription.」
3. 查不到「商業使用必須訂閱 Pro 才能隱藏」的官方原文；該說法僅見於第三方轉述，未驗證。

| 項目 | 官方原文（照抄） | 來源 |
|---|---|---|
| 授權 | "MIT License" / "Copyright (c) 2019-2025 webkid GmbH" | https://github.com/xyflow/xyflow/blob/main/LICENSE |
| 授權（官網） | "React Flow is open-source MIT-licensed software. To keep it that way, we need your support." | https://reactflow.dev/attribution |
| 法律地位 | "We can't legally require you to keep the attribution" | https://reactflow.dev/api-reference/types/pro-options |
| 政策 | "attribution visible, no subscription needed; no attribution, please subscribe." | 同上 |
| hideAttribution | "If you hide the attribution, please support our work with a subscription." | 同上（頁面標 "Last updated on October 11, 2026"） |
| Pro 去除條款 | "Subscribing to React Flow Pro permits you to remove the attribution from your flows" | https://reactflow.dev/attribution |
| Pro 價格（月） | Starter "$169 per month"；Professional "$289 per month"；Enterprise 無標價，"Request a Quote" | https://reactflow.dev/pro |
| 商業使用 FAQ | 「Can I use React Flow for my commercial project without a subscription?」答案未載入 | https://reactflow.dev/pro |
| 去除 attribution FAQ | 「Can I remove the React Flow attribution?」答案未載入 | 同上 |

## 隱藏與保留的影響（依上列來源推導，標示為推導）

- 隱藏（`proOptions={{ hideAttribution: true }}`）：技術上可行；依官方說法，合規面的要求是訂閱 Pro（請求性質，無授權條款依據）。推導：未訂閱仍隱藏，屬於違反官方請求、而非違反 MIT。
- 保留：無額外義務，右下角顯示 "React Flow" 連結。官方原文："The attribution is a small way you can give back to the team by helping us reach new developers."
- 查不到：隱藏是否影響 MIT 條款以外的商業合約（官方未給；需問 webkid GmbH 或看 Enterprise 條款）。

## 未能驗證

- 原始碼 Attribution 元件（`data-message` 字串）：GitHub 路徑回 404，未直接確認。
- `https://reactflow.dev/pro/pricing` 回 404；價格取自 https://reactflow.dev/pro。
- 第三方 README 稱「We expect companies who are using React Flow commercially to subscribe to React Flow Pro if they want to remove the attribution.」，來源 https://cdn.jsdelivr.net/npm/react-flow-vunet-new@10.3.17/README.md（v10 舊版套件，非官方）。

## 來源

- https://reactflow.dev/api-reference/types/pro-options
- https://reactflow.dev/attribution
- https://reactflow.dev/pro
- https://github.com/xyflow/xyflow/blob/main/LICENSE
