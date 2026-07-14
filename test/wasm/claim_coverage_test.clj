(ns wasm.claim-coverage-test
  "Hosts wasm/claim_coverage.wasm (compiled from wasm/claim_coverage.kotoba,
  see wasm/README.md) via kototama.tender -- proves casualty.governor's
  claim-exceeds-coverage check (`claim-exceeds-coverage-violations` in
  src/casualty/governor.cljc) runs as a real WASM guest, not just as JVM
  Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the two real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/claim_coverage.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/claim_coverage.wasm"))))

(defn- run-claim-within-coverage? [claimed-amount coverage-amount]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 claimed-amount)
    (.writeI32 memory 4 coverage-amount)
    (tender/call-main instance)))

(deftest claim-coverage-wasm-approves-well-within-coverage
  (testing "claimed-amount well below coverage-amount -> within coverage"
    (is (= 1 (run-claim-within-coverage? 200000 1000000)))))

(deftest claim-coverage-wasm-rejects-exceeding-coverage
  (testing "claimed-amount above coverage-amount -> exceeds coverage"
    (is (= 0 (run-claim-within-coverage? 1500000 1000000)))))

(deftest claim-coverage-wasm-approves-exact-boundary
  (testing "claimed-amount exactly equal to coverage-amount -> within coverage (<=)"
    (is (= 1 (run-claim-within-coverage? 1000000 1000000)))))
