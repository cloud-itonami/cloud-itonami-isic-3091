(ns motomfg.registry-test
  (:require [clojure.test :refer [deftest is]]
            [motomfg.registry :as r]))

;; ----------------------------- equipment-verified? / equipment-registered? / equipment-ready? -----------------------------

(deftest equipment-is-verified-when-flagged
  (is (true? (r/equipment-verified? {:id "e1" :verified? true}))))

(deftest equipment-is-not-verified-when-false-or-missing
  (is (false? (r/equipment-verified? {:id "e1" :verified? false})))
  (is (false? (r/equipment-verified? {:id "e1"}))))

(deftest equipment-is-registered-when-flagged
  (is (true? (r/equipment-registered? {:registered? true}))))

(deftest equipment-is-not-registered-when-false-or-missing
  (is (false? (r/equipment-registered? {:registered? false})))
  (is (false? (r/equipment-registered? {}))))

(deftest equipment-ready-requires-both
  (is (true? (r/equipment-ready? {:verified? true :registered? true})))
  (is (false? (r/equipment-ready? {:verified? true :registered? false})))
  (is (false? (r/equipment-ready? {:verified? false :registered? true})))
  (is (false? (r/equipment-ready? {}))))

;; ----------------------------- batch-verified? / batch-registered? / batch-ready? -----------------------------

(deftest batch-is-verified-when-flagged
  (is (true? (r/batch-verified? {:id "b1" :verified? true}))))

(deftest batch-is-not-verified-when-false-or-missing
  (is (false? (r/batch-verified? {:id "b1" :verified? false})))
  (is (false? (r/batch-verified? {:id "b1"}))))

(deftest batch-is-registered-when-flagged
  (is (true? (r/batch-registered? {:registered? true}))))

(deftest batch-is-not-registered-when-false-or-missing
  (is (false? (r/batch-registered? {:registered? false})))
  (is (false? (r/batch-registered? {}))))

(deftest batch-ready-requires-both
  (is (true? (r/batch-ready? {:verified? true :registered? true})))
  (is (false? (r/batch-ready? {:verified? true :registered? false})))
  (is (false? (r/batch-ready? {:verified? false :registered? true})))
  (is (false? (r/batch-ready? {}))))

;; ----------------------------- shipment-quantity-exceeded? -----------------------------

(deftest small-shipment-within-quantity-does-not-exceed
  (is (false? (r/shipment-quantity-exceeded?
               {:quantity-units 500.0 :shipped-units 100.0} 50.0))))

(deftest shipment-that-pushes-past-quantity-exceeds
  (is (true? (r/shipment-quantity-exceeded?
              {:quantity-units 80.0 :shipped-units 75.0} 10.0))))

(deftest shipment-exactly-at-quantity-does-not-exceed
  (is (false? (r/shipment-quantity-exceeded?
               {:quantity-units 80.0 :shipped-units 75.0} 5.0))
      "exactly at quantity is not over, only strictly beyond"))

(deftest missing-quantity-is-not-flagged-exceeded
  (is (false? (r/shipment-quantity-exceeded? {} 100.0)))
  (is (false? (r/shipment-quantity-exceeded? {:quantity-units 80.0} nil))))

;; ----------------------------- product-category-valid? -----------------------------

(deftest known-product-categories-are-valid
  (doseq [c [:standard-motorcycle :sport-motorcycle :cruiser-motorcycle
             :touring-motorcycle :adventure-motorcycle :naked-motorcycle
             :off-road-motorcycle :scooter :moped :electric-motorcycle]]
    (is (r/product-category-valid? c))))

(deftest fabricated-product-category-is-invalid
  (is (not (r/product-category-valid? :hoverbike)))
  (is (not (r/product-category-valid? nil))))

;; ----------------------------- engine-displacement-valid? -----------------------------

(deftest typical-engine-displacement-is-valid
  (is (r/engine-displacement-valid? 0))
  (is (r/engine-displacement-valid? 125.0))
  (is (r/engine-displacement-valid? 600.0))
  (is (r/engine-displacement-valid? 3000)))

(deftest negative-engine-displacement-is-invalid
  (is (not (r/engine-displacement-valid? -1))))

(deftest excessive-engine-displacement-is-invalid
  (is (not (r/engine-displacement-valid? 99999.0)))
  (is (not (r/engine-displacement-valid? 3001))))

(deftest non-numeric-or-missing-engine-displacement-is-invalid
  (is (not (r/engine-displacement-valid? nil)))
  (is (not (r/engine-displacement-valid? "600"))))

;; ----------------------------- defect-rate-valid? -----------------------------

(deftest typical-defect-rate-is-valid
  (is (r/defect-rate-valid? 1.5))
  (is (r/defect-rate-valid? 0.0))
  (is (r/defect-rate-valid? 50.0))
  (is (r/defect-rate-valid? 100.0)))

(deftest negative-defect-rate-is-invalid
  (is (not (r/defect-rate-valid? -1.0))))

(deftest excessive-defect-rate-is-invalid
  (is (not (r/defect-rate-valid? 999.0)))
  (is (not (r/defect-rate-valid? 100.01))))

(deftest non-numeric-or-missing-defect-rate-is-invalid
  (is (not (r/defect-rate-valid? nil)))
  (is (not (r/defect-rate-valid? "1.5"))))

;; ----------------------------- register-maintenance -----------------------------

(deftest maintenance-is-a-draft-not-a-real-actuation
  (let [result (r/register-maintenance "mnt-1" "framewelder-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-assigns-maintenance-number
  (let [result (r/register-maintenance "mnt-1" "framewelder-001" 7)]
    (is (= (get result "maintenance_number") "MNT-000007"))
    (is (= (get-in result ["record" "maintenance_id"]) "mnt-1"))
    (is (= (get-in result ["record" "equipment_id"]) "framewelder-001"))
    (is (= (get-in result ["record" "kind"]) "maintenance-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "" "framewelder-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "framewelder-001" -1))))

;; ----------------------------- register-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-dispatch
  (let [result (r/register-shipment "ship-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-shipment "ship-1" 7)]
    (is (= (get result "shipment_number") "SHP-000007"))
    (is (= (get-in result ["record" "shipment_id"]) "ship-1"))
    (is (= (get-in result ["record" "kind"]) "shipment-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "ship-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-maintenance "mnt-1" "framewelder-001" 0)
        hist (r/append [] c1)
        c2 (r/register-maintenance "mnt-2" "framewelder-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "MNT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "MNT-000001" (get-in hist2 [1 "record_id"])))))
