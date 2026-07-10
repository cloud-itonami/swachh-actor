# ADR-0001: swachh-actor — waste-ops-LLM を封じ込めたゴミ収集・分別・リサイクル actor 設計

- Status: Accepted (2026-07-10)
- 関連: gftd-talent-actor ADR-0001（HR-LLM を封じ込めた同型 actor 設計）、
  robotaxi-actor ADR-0001（研究モデルを信頼境界に封じ込める actor 設計）、
  langgraph-clj ADR-0001（Pregel superstep + interrupt + Datomic checkpoint）、
  `orgs/etzhayyim/root/20-actors/junkan`（循環／react-loop 分析 actor — 本 actor が
  分析責務を委譲する相手。`junkan/methods/waste_dynamics.cljc` がゴミ収集の
  system-dynamics ストック&フローモデルを実装する、本 actor とは別の fork）
- 文脈: インド都市部のゴミ収集・分別・リサイクルの**ビジネス実行**（収集ルート/
  ディスパッチ変更・リサイクル業者への出荷・処理施設への振り分け）の意思決定支援。
  親 superproject（com-junkawasaki/root）の調査で、cloud-itonami / kotoba-lang
  いずれにもこのドメインに特化した actor が存在しないことを確認した上での新規設計。
  **設計変更（2026-07-10、オーナー協議の結果）**: 当初案は本 actor に system
  dynamics（ストック&フロー・react loop分析）まで含める予定だったが、調査の過程で
  `orgs/etzhayyim/root/20-actors/junkan`（循環）に社会フィードバックループ観測専用の
  actor が既に存在し、インド packaged-goods 文化向けの節まで持つことが判明した。
  そのため「ハイブリッド分割」を採用: system dynamics 分析は `junkan` 側
  （別フォークが `junkan/methods/waste_dynamics.cljc` として拡張実装、analysis-only
  ・actuator なし）に委譲し、本 actor はゴミ収集・分類・リサイクルの**ビジネス実行**
  （収集ルート変更・出荷・処理施設振り分けの提案 + governance）に専念する。詳細は
  下記「4. system dynamics 分析は junkan の責務」を参照。

## 課題

インドの都市ゴミ問題は「収集能力 < 発生量」の慢性的なキャパシティ不足が街路への
ゴミ滞留（衛生リスク・疫病リスク）を生み、同時に分別・リサイクル業への出荷は
インフォーマルな屑拾い（kabadiwala）労働者の個人情報保護と、無登録業者への横流し
防止という2つの実務上の compliance 課題を抱える。この actor は:

1. **収集/出荷/ルート意思決定の実行支援** — 収集ゾーンのディスパッチ更新、どの
   分別済みバッチをどの業者へ出荷するか、そしてゾーンの収集優先度（route
   priority）変更を LLM がドラフトしつつ、無登録業者への出荷を構造的に防ぐ。
   ルート優先度提案は `junkan`（別 actor）が計算した街路滞留ゴミの react-loop
   分析結果（悪循環判定・leverage point 等）を**入力として受け取る**が、その
   モデル自体は計算しない（下記「4.」参照）。
2. **インフォーマル労働者の保護** — ゾーン担当の収集人（kabadiwala）の氏名・
   Aadhaar・住所を、判断根拠や公開帳票に漏出させない。

一方、収集ルート/出荷提案の生成には LLM が有効だが、**LLM に直接書き込み/開示
させるのは危険**（無登録業者の推薦、個人情報の根拠利用・過剰開示、権限を越えた
更新、幻覚）。したがって設計課題は「LLM でゴミ収集業務を回す」ことではなく、
**gftd-talent-actor / robotaxi-actor と同型に、LLM を信頼境界の内側に封じ込め、
コンプライアンス・監査・人間承認の層をどう被せるか**である。

## 決定

### 1. waste-ops-LLM は最下層の1ノードに封じ込め、直接書き込み/開示させない

`swachh.operation/build` の `:advise` ノードで waste-ops-LLM (`swachh.wasteops`)
は *proposal*（収集dispatchドラフト・出荷先/数量提案・ダイナミクス分析結果 +
根拠トレース）のみを返す**助言者**として扱う。出力は必ず独立した
`SanitationGovernor`（`swachh.governor`）を通してから台帳に commit する。
**単一の不変条件**:

> **waste-ops-LLM は、SanitationGovernor が拒否する書き込み・開示を決して行わない。**
> **actor は、業者への支払い確定や物理的な収集車両の作動を一切行わない
> （このリポジトリの scope に payment/hardware-dispatch 経路は存在しない）。**
> **actor は自ら stock/flow モデルを計算・保有しない — react-loop 分析は常に
> 別 actor（junkan）からの入力として受け取るだけ（下記「4.」参照）。**

これは gftd-talent-actor の「HR-LLM は PolicyGovernor が拒否する人事レコードの
書き込み/開示を決して行わない」、robotaxi の「AR1 は SafetyGovernor が拒否する
軌道を作動させない」とそのまま同型。

### 2. OperationActor = langgraph-clj StateGraph、1 run = 1 waste-ops操作

```
intake → advise(waste-ops-LLM) → govern(SanitationGovernor) → decide ─┬─ ok・確信・低リスク ──────▶ commit → END
                                                                      ├─ 重大操作 / 低確信 ─▶ request-approval
                                                                      │                       [interrupt-before]
                                                                      │                       ward-officer がレビュー
                                                                      │                       resume ─▶ commit | hold
                                                                      └─ 規程違反(権限/目的/個人情報/業者適格) ─▶ hold → END
```

`swachh.operation` は gftd-talent-actor の `talent.operation` と1:1のグラフ形状
（`talent.hrllm`→`swachh.wasteops`、`talent.policy`→`swachh.governor`、
`talent.phase`→`swachh.phase`、`talent.store`→`swachh.store` の直接対応）。

### 3. SanitationGovernor は waste-ops-LLM と別系統（6チェック）

| 責務 | 機構 |
|---|---|
| 権限分離 (RBAC) | actor の role が「その操作 × その対象ゾーン」に権限を持つか |
| 利用目的拘束 | 目的が宣言され、自治体の同意/法的根拠があるか |
| **収集人プライバシー**（fairness gate の domain analog） | 提案の根拠にゾーン担当収集人（kabadiwala）の個人情報を引いていないか |
| **業者適格性**（本 actor 固有の新規チェック） | 出荷提案の vendor が登録済み（licensed）か |
| 個人情報の最小開示 | 帳票/開示が目的に対し過剰な列（収集人PII等）を含まないか |
| 確信度フロア / 重大操作ゲート | 低確信 or 大口出荷/衛生エスカレーション → 人間承認へ escalate |

最初の4つは**規程違反（HARD）で人間承認では上書きできず hold に固定**、後の2つは
**SOFT（人間が承認できる）**。gftd-talent-actor と同じ優先順位構造。

### 4. system dynamics 分析は `junkan`（別 actor）の責務 — 本 actor は実行に専念

**設計判断**: 街路滞留ゴミ（`uncollected`）のストック&フロー・react-loop 分析
（Forrester stock-flow モデルによる悪循環判定・leverage point 特定など）は
**この actor に実装しない**。`orgs/etzhayyim/root/20-actors/junkan`（循環）が
社会フィードバックループ観測専用の analysis-only actor として既に存在し、
インド packaged-goods 文化向けの節まで持っていたため、`junkan/methods/
waste_dynamics.cljc`（別フォークが拡張実装、actuator なし・分析専用）に
system dynamics の実装責務を委譲する。

**理由（このエコシステムの"分析/介入分離"原則に倣う）**: このワークスペースは
既に「分析専用 actor（例: junkan — 観測・投影・react-loop 判定のみ、書き込み/
作動なし）」と「介入・実行 actor（例: ossekai 等 — governor に守られた実際の
書き込み/ディスパッチを行う）」を分離する設計原則を持つ。ストック&フロー分析
（何が悪循環か、どこに leverage point があるか）と、ビジネス実行（どのゾーンを
優先収集するか、どの業者に出荷するか）は本質的に異なる責務であり、前者を
1つの分析専用 actor に集約すれば：
  - 複数の実行 actor（本 actor に限らず将来のゴミ関連 actor 全般）が同じ分析
    モデル・同じ検証済み実装を再利用できる（DRY）。
  - 分析ロジックの変更が実行 actor の governor/監査台帳に影響しない（責務境界が
    明確）。
  - `cloud_itonami.mes.system_dynamics`（製造業在庫向け）のような既存の別ドメイン
    実装を、ゴミドメイン向けに毎回フォーク・重複実装せずに済む。

**本 actor 側の consuming 契約**: `swachh.wasteops` の `:route/propose` op が、
ゾーンの `:observed-backlog`（供給者が報告する生の観測値、モデル出力ではない）
と、`(swachh.store/insight-of db zone-id)` — junkan が最後に書き込んだ分析結果
（例: `{:vicious-cycle? true :leverage-point :collection-capacity}`）— の両方を
事実として読み、収集優先度（`:route-priority`）の変更を提案する。**この actor は
junkan のコードを直接呼び出す統合はしない**（過剰結合を避ける — `:insight-of`
はただの疎結合な EDN マップの読み取りチャネル）。junkan 側の悪循環判定は常に
`:hygiene-escalation` として人間承認（ward officer）へ回る（phase/governor 双方
でエスカレーション対象）。junkan insight が未取得のゾーンでも `:observed-backlog`
と `:collection-capacity` の比だけで動作を継続できるようフォールバックする。

### 5. Store / Advisor / Phase はすべて injection（swap、rewrite でない）

- Store: `MemStore`（既定・デモ用）‖ `DatomicStore`（`langchain.db`、Datomic Local /
  kotoba-server pod に差し替え可能）。両者は同一契約テスト
  (`store_contract_test.cljc`) で保証。
- Advisor: `mock-advisor`（既定・決定的）‖ `llm-advisor`（`langchain.model`
  ChatModel、Anthropic/OpenAI互換）。
- Phase: 0(read-only) → 1(assisted-dispatch) → 2(+route) →
  3(supervised-auto)。robotaxi の ODD 拡大・talent の Phase 0→3 と同型。

## Scope（このリポジトリで実装していないもの）

- **system dynamics ストック&フローモデルの実装**（決定4参照）— `junkan`
  （`orgs/etzhayyim/root/20-actors/junkan`、別フォーク）の責務。本 actor は
  `swachh.store/insight-of` 経由でその出力を読むだけで、モデル自体・検証
  （closed-form/Euler収束/質量保存テスト等）は一切持たない。
- junkan との実統合（実際に junkan のコードを呼び出す/API 接続する経路）— 疎結合
  な EDN insight チャネルの読み取り契約のみ実装し、実配線は follow-up。
- 実データ接続（実際の自治体GIS/収集ルートDB との統合）、実LLM接続の運用設定 —
  gftd-talent-actor の `facts.cljc`（m365-archive seed adapter）に相当する外部
  データ取り込みは未実装（Phase 0 相当のスキャフォールドとして意図的にスコープ外）。
- 業者への支払い/決済確定、物理的な収集車両・ロボットの作動 — actor の書き込み
  surface に一切含まれない（決定1参照）。将来これらが必要になっても、governor が
  拒否できない形で actor に持たせてはならない。
- RAD identity 登録（`etzhayyim/root` の `80-data/kotoba-rad/`）— follow-up として
  残す（CLAUDE.md Actors 節）。

## 帰結

- **得るもの**: junkan の分析（react loop）を入力とした収集ルート優先度の早期
  意思決定、無登録業者への横流し防止、インフォーマル労働者のプライバシー保護、
  不変の監査台帳、LLM支援（dispatch/出荷提案/ルート提案）と人間最終承認の両立。
- **負うもの**: 自前運用（Datomic 運用・licensed-vendor 表/権限表の保守・LLM推論
  基盤・実データ統合・junkan との実配線は別途実装が必要）。
- **段階導入**: Phase 0 read-only → Phase 1 dispatch/出荷提案（人間承認必須）→
  Phase 2 +ルート提案 → Phase 3 確信度の高い定型操作のみ自動 commit。

## 代替案と不採用理由

- **LLM に書き込み権限を直接付与（エージェント自律）**: 速いが、無登録業者への
  出荷・個人情報の根拠利用・過剰開示を構造的に防げない。単一不変条件（決定1）に反する。
- **収集ルート最適化を単体の最適化アルゴリズムとして実装（actor 層なし）**: 作れるが
  「LLM を封じ込める信頼境界」「監査台帳」「規程ゲート」という actor の核が構造として
  現れず、gftd-talent-actor/robotaxi-actor と同型の再利用可能なガバナンス層を持てない。
- **本 actor に system dynamics（ストック&フロー）モデルを自前実装する（当初案）**:
  決定4で述べたとおり、既存の `junkan` 分析専用 actor と責務が重複し、この
  ワークスペースの分析/介入分離原則に反する。分析ロジックの二重実装・二重保守
  コストも避けられない。
