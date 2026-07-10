# swachh-actor

A waste-collection / sorting / recycling **business-execution actor design**
for Indian urban municipalities — the OSS governance layer that lets a
generative model (`waste-ops-LLM`) draft collection dispatch changes,
recycling-vendor shipment recommendations and collection-route priority
changes, **without ever being trusted to write, disclose or act on its
own**. Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as
[`robotaxi-actor`](../../com-junkawasaki/robotaxi-actor) and
[`gftd-talent-actor`](../../gftdcojp/gftd-talent-actor).

> **Scope note (analysis/intervention split)**: this actor does **not**
> implement a system-dynamics stock/flow model of street-level waste
> backlog. That analysis is a separate actor's responsibility —
> [`junkan`](../root/20-actors/junkan) (循環), extended by
> `junkan/methods/waste_dynamics.cljc` for this domain — following this
> workspace's analysis(junkan)/intervention(execution actors) separation
> principle. swachh reads junkan's analysis output (a vicious-cycle /
> leverage-point verdict) via `swachh.store/insight-of` as one more decision
> input, alongside the zone's raw `:observed-backlog` reading, when
> proposing a route-priority change. See
> [`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) §4.

> **Why an actor layer at all?** A waste-ops-LLM is great at drafting
> collection-route/dispatch patches, recommending which recycler a sorted
> batch should ship to, and folding a separate analysis actor's hygiene-risk
> verdict into a routing call — but it has **no notion of vendor legitimacy,
> purpose-limitation, or the privacy of the informal waste-pickers
> (kabadiwala) who work each zone**.
> Letting it act directly invites routing municipal waste to unregistered
> handlers, citing a worker's identity as a decision basis, and over-
> disclosing personal data in public reports. This project seals the
> waste-ops-LLM into a single node and wraps it with an independent
> **SanitationGovernor**, a human **approval workflow** (ward sanitation
> officer), and an immutable **audit ledger**.

See [`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture decision record.

## The core contract

```
request + injected RBAC/purpose/consent context
        │
        ▼
   ┌───────────┐    proposal      ┌────────────────────┐
   │waste-ops- │ ───────────────▶ │ SanitationGovernor  │  (independent system)
   │LLM(sealed)│  draft+rationale │ RBAC·privacy·vendor │
   └───────────┘                  └──────────┬──────────┘
                            commit ◀──────────┼──────────▶ hold (規程違反; 上書き不可)
                                │                          │
                          SSoT + 台帳                  escalate ─▶ 人間承認 (interrupt)
```

**waste-ops-LLM never commits or discloses a record the SanitationGovernor
would reject**, and it never confirms vendor payment or dispatches physical
collection hardware — those are outside this actor's write surface
entirely. That single invariant is what lets a generative model assist
waste operations. Hard violations (permission / purpose / worker-privacy /
unlicensed-vendor / over-disclosure) fall back to **hold** and *cannot* be
overridden by a human; only soft cases (low confidence / high-stakes) go to
the approval workflow.

## Run

```bash
clojure -M:dev:run     # drive collection/dispatch, shipment, route ops through one OperationActor
clojure -M:dev:test    # governor contract · store parity · LLM advisor · phases
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

Demo output walks four operations: a collection-capacity dispatch update
(committed) → a shipment proposal that cites the zone's kabadiwala's
identity as its basis (**worker-privacy reject → hold**) → a shipment
proposal recommending an unregistered vendor (**vendor-eligibility reject →
hold**) → a Dharavi-zone route-priority proposal, after seeding a junkan
vicious-cycle insight for that zone (**escalate → ward-officer approves →
commit**), then prints the immutable audit ledger.

## Layout

| File | Actor / role |
|---|---|
| `src/swachh/wasteops.cljc` | **Advisor** protocol — `mock-advisor` (default) ‖ `llm-advisor` (real `langchain.model` ChatModel) |
| `src/swachh/governor.cljc` | **SanitationGovernor** — RBAC · purpose · worker-privacy · vendor-eligibility · minimal-disclosure · escalation |
| `src/swachh/phase.cljc` | **Phase 0→3 rollout** — read-only → assisted-dispatch → assisted-route → supervised-auto |
| `src/swachh/operation.cljc` | **OperationActor** — langgraph-clj StateGraph (1 run = 1 waste-ops op); Store/Advisor/Phase injected |
| `src/swachh/store.cljc` | **Store** protocol — `MemStore` (default) ‖ `DatomicStore` (`langchain.db`, swappable to Datomic Local / kotoba-server) + append-only ledger; `insight-of` is the read-only channel for a separate analysis actor's (junkan) output |
| `src/swachh/report.cljc` | **ReportActor** — governed CSV export + zone status view |
| `src/swachh/sim.cljc` | demo driver |
| `test/swachh/*_test.cljc` | governor contract · store parity (Mem≡Datomic) · LLM advisor · phase rollout |

**Not in this repo**: any system-dynamics stock/flow model. That is
`junkan`'s responsibility (`orgs/etzhayyim/root/20-actors/junkan`,
`junkan/methods/waste_dynamics.cljc`) — see the scope note above and
`docs/adr/0001-architecture.md` §4.

## Domain model

- **Zone** — a municipal collection zone: `:observed-backlog` (a plain
  supervisor-reported number, not a simulated stock), `:collection-capacity`,
  the assigned collection-supervisor, an optional `:route-priority` set by
  a committed `:route/propose`, and the zone's `:collector` (informal
  waste-picker) identity — the protected entity the SanitationGovernor's
  worker-privacy gate exists to defend.
- **Vendor** — a recycling business partner, `:licensed?` or not. Only
  licensed vendors may be proposed as a shipment destination.
- **Operations**: `:collection/dispatch` (capacity/supervisor patch),
  `:shipment/propose` (which vendor + how much), `:route/propose`
  (collection-priority change, consuming junkan's analysis insight via
  `store/insight-of` when present), `:report/export` (governed
  CSV/dashboard).

## 本番バックエンドへの差し替え（すべて injection）

actor が依存する3点はどれも *swap* で、コア（OperationActor /
SanitationGovernor / 監査台帳）は触らない:

```clojure
;; SSoT: in-mem → Datomic（langchain.db。さらに :db-api で実 Datomic Local /
;;       kotoba-server pod に差し替え可）
(def store (store/datomic-seed-db))

;; Advisor: mock → 実 LLM（Anthropic / OpenAI互換=Ollama・vLLM・kotoba）
(require '[langchain.model :as model])
(def actor (op/build store {:advisor (wasteops/llm-advisor
                                       (model/anthropic-model {:api-key (System/getenv "ANTHROPIC_API_KEY")
                                                               :model "claude-sonnet-4-6"}))}))

;; Phase: context に :phase 0..3 を載せるだけ（既定 3）
(g/run* actor {:request req :context (assoc ctx :phase 1)} {:thread-id id})
```

LLM 応答が壊れても `parse-proposal` が confidence 0 の noop に落とすため、
**LLM の不調が自動 commit になることはない**（governor が必ず escalate/hold）。

## Status

設計 scaffold 完了。runnable + governor contract / store parity / LLM
advisor / phase rollout の各テストスイートあり。
残り（intentionally out of scope — 詳細は ADR-0001 §Scope）: junkan との実配線、
実自治体データ（GIS/収集ルートDB）への接続、実 Datomic Local / kotoba-server
pod・実 LLM エンドポイントでの結合確認、RAD identity 登録。

## License

Code is licensed under AGPL-3.0-or-later.
