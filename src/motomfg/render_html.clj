(ns motomfg.render-html
  "Build-time HTML renderer for the operator console.

  Drives the REAL MotoOperationActor (`motomfg.operation/build` -> a
  compiled langgraph-clj StateGraph) over the REAL seeded store
  (`motomfg.store/sample-data!`), through the REAL Motorcycle Plant
  Operations Governor (`motomfg.governor/check`) and the REAL rollout
  phase gate (`motomfg.phase/gate`), and renders whatever those
  produced. Nothing on the page is written by hand:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`,
      `store/safety-concerns`, `store/maintenance-history`,
      `store/shipment-history`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase gate table is derived from `motomfg.phase/phases`, and
      the governor configuration / ground-truth bound tables from
      `motomfg.governor` and `motomfg.registry` public vars.

  Subject provenance (the demo may not invent subjects): every batch
  and equipment id driven below is either seeded by
  `store/sample-data!` (`batch-001` `batch-002` `batch-003`
  `framewelder-001` `dynobench-002`) or created by an intake op inside
  this demo itself -- `batch-004` exists only because the `t01`
  `:log-production-batch` commit created it -- and every `mnt-*` /
  `ship-*` / `concern-*` subject is the draft record its own op
  registers via `motomfg.registry`.

  Fields rendered are only fields the domain model actually carries. In
  particular `:approved-by` is NOT rendered on a committed maintenance
  / shipment record: `motomfg.operation`'s `:request-approval` node puts
  the approver on the record's `:payload`, while `store/commit-record!`
  persists `:value` -- so the approver is shown from the run timeline
  (where it is real), not from the stored record (where it does not
  exist).

  Styling is the workspace base design system
  (`jp-go-dds.skin/dds+skin`, デジタル庁デザインシステム) plus a few
  layout-only rules that reference DADS tokens and no raw colour.

  Deterministic: no clock, no randomness, no network. Re-running writes
  a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [motomfg.governor :as governor]
            [motomfg.operation :as op]
            [motomfg.phase :as phase]
            [motomfg.registry :as registry]
            [motomfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`). Several HARD
  scenarios carry an `:approval` deliberately: it is never offered,
  because the graph never pauses on a hold -- the timeline says so."
  [{:tid "t01"
    :exercises "Intake of a NEW production batch. Governor-clean, and :log-production-batch is the one op in phase 3's :auto set -> auto-commit, no human. batch-004 exists for the rest of this page only because this op created it. Its 0 cc displacement is a real registry edge: an electric motorcycle legitimately reports no internal-combustion displacement."
    :request {:op :log-production-batch :effect :propose :subject "batch-004"
              :patch {:product-category :electric-motorcycle
                      :model-code "EV-500"
                      :engine-displacement-cc 0.0
                      :quantity-units 60.0
                      :weld-defect-rate-percent 1.2
                      :last-assessed "2026-07-20"}}}

   {:tid "t02"
    :exercises "Maintenance window against a verified + registered frame-welding robot. Governor-clean, but :schedule-maintenance is never in any phase's :auto set -> escalates; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "framewelder-001"
                      :maintenance-type :torch-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Safety concern. Always high-stakes, so the governor escalates regardless of confidence; the human approves. A concern is never gated on the equipment being verified."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "framewelder-001" :severity :moderate
                      :description "フレーム溶接部の異音、ブレーキマウントの位置ずれ兆候"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Shipment against a verified + registered batch with headroom. Escalates; the human shipping approver approves and the batch's own shipped-units advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :units 50.0
                      :destination "dealer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "A governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not -- and batch-002's shipped-units stays put."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-002" :units 4.0
                      :destination "dealer-yard-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against batch-004 -- the batch t01 just created, which carries no verified?/registered? ground truth of its own. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-004" :units 10.0
                      :destination "dealer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment whose claimed units would push batch-002 past its own recorded production quantity. The governor recomputes from the batch's own fields, never from the proposal's claim. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-002" :units 10.0
                      :destination "dealer-yard-east"}}}

   {:tid "t08"
    :exercises "Maintenance against the seeded emissions-dyno test bench, which is neither inspected nor on file. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "dynobench-002"
                      :maintenance-type :load-cell-calibration
                      :scheduled-date "2026-08-05"
                      :actuate-equipment? false}}}

   {:tid "t09"
    :exercises "A maintenance proposal that tries to ACTUATE the welding equipment rather than draft a window. Permanent scope boundary -- the approval below is never offered, because a hold never pauses the graph. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "framewelder-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-equipment? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t10"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "framewelder-001"
                      :maintenance-type :torch-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}}

   {:tid "t11"
    :exercises "A batch patch declaring a product category outside the closed known set. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:product-category :hoverbike}}}

   {:tid "t12"
    :exercises "A batch patch with a rated engine displacement far outside any physically plausible motorcycle engine. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:engine-displacement-cc 99999.0}}}

   {:tid "t13"
    :exercises "A batch patch claiming a frame-weld defect rate above 100%. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:weld-defect-rate-percent 999.0}}}

   {:tid "t14"
    :exercises "A patch trying to self-issue an ECE R78 / ECE R40 motorcycle type-approval certification. Authority this actor never holds -- permanent, and the approval below is never offered. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:issue-certification? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t15"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-category :sport-motorcycle}}}

   {:tid "t16"
    :exercises "An op outside the closed allowlist. Both the op allowlist and the proposal-effect allowlist reject it. HARD hold."
    :request {:op :actuate-welding-line :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model has no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"n num\">" (esc value) "</span>"
       "<span class=\"l muted\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger "
               "after driving " (count runs) " requests through "
               (code "motomfg.operation/build") ".")
          (str "<div class=\"stats\">"
               (stat "requests driven" (count runs))
               (stat "ledger facts" (count led))
               (stat "commits" (n :committed))
               (stat "governor HARD holds" (n :governor-hold))
               (stat "human approvals" (count (filter #(= :approved (:human %)) runs)))
               (stat "human rejections" (count (filter #(= :rejected (:human %)) runs)))
               "</div>"
               "<p class=\"muted\">Note: <code>:approval-granted</code> is emitted to the graph's "
               "in-memory <code>:audit</code> channel only — <code>motomfg.operation</code> never "
               "appends it to the store ledger, so it is not a fact this page counts. An approved "
               "request is visible as the <code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. "
             "The governor column is the verdict map the governor itself returned; the human "
             "column is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ".")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is a <code>:governor-hold</code> fact on the append-only ledger. The rule "
               "name and the detail text are the governor's own "
               (code ":violations") " entries — this page holds no rule text of its own. A HARD "
               "hold never reaches a human: no phase and no approval can override it.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "motomfg.phase/phases") ". A governor HOLD always stays a "
               "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
               "the governor is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "motomfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "motomfg.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid product categories" (kw-codes registry/valid-product-categories))
                (tr "engine displacement (cc)"
                    (str (code registry/engine-displacement-min-cc) " … "
                         (code registry/engine-displacement-max-cc)))
                (tr "weld defect rate (%)"
                    (str (code registry/defect-rate-min-percent) " … "
                         (code registry/defect-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining [b]
  (let [q (:quantity-units b) s (:shipped-units b 0.0)]
    (if (and (number? q) (number? s)) (- (double q) (double s)) nil)))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "motomfg.store/all-batches") " after the run. "
               (code "batch-001") " " (code "batch-002") " " (code "batch-003")
               " are seeded by " (code "store/sample-data!") "; " (code "batch-004")
               " exists because the <code>t01</code> intake op committed it. "
               "A field the record does not carry shows as —; " (code "batch-004")
               " has no " (code ":shipped-units") " of its own yet, so <em>Remaining</em> uses "
               "the same <code>0.0</code> default " (code "motomfg.registry") " itself applies "
               "when it recomputes headroom. <em>Ledger status</em> is the LAST fact whose "
               (code ":subject") " is that batch id — a shipment keys its facts on the "
               "shipment-draft id instead, which is why " (code "batch-002")
               " shows no activity of its own despite two shipment attempts against it.")
          (table ["Batch" "Category" "Model" "Displacement (cc)" "Quantity (units)"
                  "Shipped (units)" "Remaining" "Weld defect rate (%)" "verified?" "registered?"
                  "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:product-category b)) (fmt (:model-code b))
                       (fmt (:engine-displacement-cc b)) (fmt (:quantity-units b))
                       (fmt (:shipped-units b)) (fmt (remaining b))
                       (fmt (:weld-defect-rate-percent b))
                       (flag (:verified? b)) (flag (:registered? b))
                       (if (registry/batch-ready? b)
                         "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Welding / assembly / test-bench equipment"
        (str "Read back from " (code "motomfg.store/all-equipment") ". Equipment ids are never a "
             "request <code>:subject</code> in this domain (a maintenance draft id is), so no "
             "ledger-status column is shown for them — "
             (code ":last-scheduled-maintenance-date") " is the field the commit path actually "
             "writes onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (if (registry/equipment-ready? e)
                       "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (esc (count (filter #(= (:id e) (:equipment-id %))
                                         (store/all-maintenance db)))))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)
        hist (store/maintenance-history db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "motomfg.store/all-maintenance") ", with the "
               "immutable record " (code "motomfg.registry/register-maintenance") " minted at "
               "commit time (" (esc (count hist)) " on file in "
               (code "store/maintenance-history") "). Nothing here actuates any equipment.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-equipment?"
                    "scheduled?" "Maintenance number" "Registry record kind"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-equipment? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m))
                         (fmt (some #(when (= (:id m) (get % "maintenance_id"))
                                       (get % "kind"))
                                    hist)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "motomfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Units" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s)) (fmt (:units s))
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log (" (code "motomfg.store/safety-concerns")
               "). A concern may be raised against any equipment, verified or not — it is never "
               "blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as "
             (code "motomfg.store/ledger") " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(def ^:private layout-css
  "Layout-only additions on top of `jp-go-dds.skin`. DADS tokens only —
  no raw colour value is written here (see the workspace UI rule: the
  design system owns colour, an app owns layout)."
  (str ".stats{display:flex;flex-wrap:wrap;gap:.75rem;margin:.5rem 0 1rem}"
       ".stat{border:1px solid var(--color-neutral-solid-gray-200);border-radius:8px;"
       "padding:.6rem .9rem;min-width:9rem}"
       ".stat .n{display:block;font-size:1.5rem;font-weight:700;line-height:1.3}"
       ".stat .l{display:block;font-size:.8125rem}"
       ".bar{flex-wrap:wrap}"))

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-3091 (motomfg)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "\n" layout-css "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Motorcycle plant operations — operator console</h1>"
       "<span class=\"badge\">ISIC 3091</span>"
       "<span class=\"badge\">motomfg</span>"
       "</header>\n"
       "<p class=\"subtitle\">Governor "
       (code "motorcycle-plant-operations-governor") " · actor "
       (esc (:actor-id coordinator)) " · role " (code (:actor-role coordinator))
       " · phase " (esc (:phase coordinator))
       " · read-only sample, governor-gated, welding/assembly/test-bench actuation and ECE "
       "R78/R40 type-approval self-issuance permanently blocked.</p>\n"
       "<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>motomfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>motomfg.operation</code> actor graph over the real "
       "<code>motomfg.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
