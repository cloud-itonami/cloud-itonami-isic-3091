(ns motomfg.registry
  "Pure-function domain logic for the motorcycle-manufacturing
  plant-operations coordination actor -- equipment/batch
  verification, shipment-quantity recompute, product-category
  validation, engine-displacement plausibility validation,
  weld-defect-rate plausibility validation, and draft
  maintenance-schedule/shipment-coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/motomfg`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by
  `motomfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `bikemfg.registry/shipment-quantity-exceeded?` from
  `cloud-itonami-isic-3092`, `tyremfg.registry` from
  `cloud-itonami-isic-2211`): never trust a proposal's own
  self-reported quantity/status when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating welding/assembly/
  test-bench equipment or dispatching a real freight carrier, and
  never the act of issuing an ECE R78 (motorcycle brake system) / ECE
  R40 (motorcycle emissions) type-approval certification (this actor
  NEVER does any of those -- see README `What this actor does NOT
  do`).

  SCOPE: ISIC 3091 covers manufacture of motorcycles -- frame-welding,
  engine-assembly, final-assembly, and test-bench (structural/brake/
  emissions-dyno) inspection lines producing finished motorcycles
  (standard/sport/cruiser/touring/adventure/naked), scooters, mopeds,
  off-road motorcycles and electric motorcycles. This actor
  coordinates the back-office record-keeping around that plant
  (production-batch logging, maintenance scheduling, safety-concern
  flagging, shipment coordination) -- it never touches the welding/
  assembly/test-bench equipment directly, and it never stands in for
  the type-approval authority that issues motorcycle brake/emissions
  certification marks.")

;; ----------------------------- constants -----------------------------

(def valid-product-categories
  "The closed set of product-category values a production-batch record
  may declare -- spans motorcycles, scooters, mopeds and electric
  motorcycles, the product families ISIC 3091 covers. Anything else is
  a fabricated/unrecognized product category -- the governor HARD-holds
  rather than let an invented category pass through."
  #{:standard-motorcycle :sport-motorcycle :cruiser-motorcycle
    :touring-motorcycle :adventure-motorcycle :naked-motorcycle
    :off-road-motorcycle :scooter :moped :electric-motorcycle})

(def engine-displacement-min-cc
  "Physical floor for a motorcycle batch's own rated engine
  displacement (electric motorcycles carry no internal-combustion
  displacement and legitimately report 0)."
  0)

(def engine-displacement-max-cc
  "Physical ceiling for a motorcycle batch's own rated engine
  displacement (spans mopeds/scooters through the largest production
  touring/cruiser engines). A reading above this is an implausible/
  fabricated rating, not a real standardized displacement spec."
  3000)

(def defect-rate-min-percent
  "Physical floor for a batch's own frame-weld/assembly defect-rate
  reading (zero defects is the best possible outcome, never negative)."
  0.0)

(def defect-rate-max-percent
  "Physical ceiling for a batch's own frame-weld/assembly defect-rate
  reading -- a batch cannot reject more than 100% of its own output. A
  reading above this is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-category/engine-displacement/quantity/
  weld-defect-rate claims have actually been QC-inspected, not merely
  logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-quantity-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-units` + `new-units` exceed `batch`'s own recorded
  `:quantity-units` (the batch's own logged production quantity)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-units]
  (let [capacity (:quantity-units batch)
        so-far (:shipped-units batch 0.0)]
    (and (number? capacity)
         (number? new-units)
         (> (+ (double so-far) (double new-units)) (double capacity)))))

(defn product-category-valid?
  "Is `product-category` one of the closed, known product-category
  values (motorcycles, scooters, mopeds, electric motorcycles)?
  nil/blank is treated as invalid (a production-batch patch must
  declare a real product category, not omit it silently)."
  [product-category]
  (contains? valid-product-categories product-category))

(defn engine-displacement-valid?
  "Is `engine-displacement-cc` a physically plausible rated engine
  displacement? Rejects nil, non-numbers, negative values, and values
  beyond `engine-displacement-max-cc` -- a fabricated or sensor-error
  reading, never let through as a real product-rating fact."
  [engine-displacement-cc]
  (and (number? engine-displacement-cc)
       (>= (double engine-displacement-cc) (double engine-displacement-min-cc))
       (<= (double engine-displacement-cc) (double engine-displacement-max-cc))))

(defn defect-rate-valid?
  "Is `percent` a physically plausible batch frame-weld/assembly
  defect-rate reading? Rejects nil, non-numbers, negative values, and
  values beyond `defect-rate-max-percent` -- a fabricated or
  sensor-error reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) defect-rate-min-percent)
       (<= (double percent) defect-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's. And NEVER an ECE R78 / ECE R40 motorcycle type-approval
  certification mark -- this actor is never the type-approval
  authority (see README `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  welding/assembly/test-bench maintenance window against a verified,
  registered piece of equipment. Pure function -- does not actuate the
  welding/assembly/test-bench equipment or execute any maintenance; it
  builds the RECORD a plant coordinator would keep. `motomfg.governor`
  independently re-verifies the equipment's own verified/registered
  ground truth, and permanently blocks any attempt to directly actuate
  the welding/assembly/test-bench equipment (see README `Actuation`),
  before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound motorcycle shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `motomfg.governor` independently re-verifies the shipment's
  own claimed quantity against `shipment-quantity-exceeded?`, before
  this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
