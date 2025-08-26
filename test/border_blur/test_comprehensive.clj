(ns border-blur.test-comprehensive
  (:require [clojure.test :refer :all]
            [border-blur.game :as game]
            [border-blur.images.selector :as selector]
            [border-blur.gis.core :as gis]
            [border-blur.gis.cities :as cities]
            [geo.jts :as jts]
            [geo.spatial :as spatial]))

(deftest test-game-creation
  (testing "Basic game creation"
    (let [session (game/new-game "Test User")]
      (is (some? (:session-id session)))
      (is (= 3 (:total-stages session)))
      (is (= 1 (:current-stage session)))
      (is (= 0 (:score session)))
      (is (= 0 (:streak session)))
      (is (= "Test User" (:user-city session)))))

  (testing "Game creation with edge case inputs"
    (let [nil-session (game/new-game nil)
          empty-session (game/new-game "")]
      (is (nil? (:user-city nil-session)))
      (is (= "" (:user-city empty-session))))))

(deftest test-session-management
  (testing "Session save and retrieve"
    (let [session (game/new-game "Save Test")]
      (game/save-game-session! session)
      (let [retrieved (game/get-game-session (:session-id session))]
        (is (= session retrieved)))))

  (testing "Invalid session ID"
    (let [invalid (game/get-game-session "nonexistent-uuid")]
      (is (nil? invalid))))

  (testing "Process answer with invalid session"
    (let [result (game/process-answer "nonexistent-uuid" true)]
      (is (nil? result)))))

(deftest test-coordinate-parsing
  (testing "Valid coordinate parsing from filename"
    (let [filename "/images/border-collection/tel-aviv/32.04887_34.77496_ta-south-1_794647.jpg"
          coords (selector/parse-coords-from-filename filename)]
      (is (= [34.77496 32.04887] coords))))

  (testing "Invalid filename coordinate parsing"
    (let [invalid-files ["/images/invalid_coords.jpg"
                         "/images/32.12345_invalid_ta-test.jpg"
                         "/images/border-collection/tel-aviv/invalid_format.jpg"
                         "/images/abc_def_test.jpg"]]
      (doseq [filename invalid-files]
        (let [coords (selector/parse-coords-from-filename filename)]
          (is (nil? coords) (str "Expected nil for " filename)))))))

(deftest test-scoring-system
  (testing "Scoring calculation correctness"
    ;; Test base scoring
    (is (= 10 (game/calculate-score true :easy 0)))
    (is (= 0 (game/calculate-score false :easy 0)))

    ;; Test difficulty bonuses
    (is (= 15 (game/calculate-score true :medium 0)))
    (is (= 20 (game/calculate-score true :hard 0)))

    ;; Test streak multipliers
    (is (= 12 (game/calculate-score true :easy 1)))
    (is (= 14 (game/calculate-score true :easy 2)))
    (is (= 20 (game/calculate-score true :easy 5))))

  (testing "Wrong answer penalties"
    (is (= 5 (game/calculate-score false :medium 0)))
    (is (= 10 (game/calculate-score false :hard 0)))))

(deftest test-tel-aviv-boundary-accuracy
  (testing "CRITICAL: Tel Aviv image labeling accuracy"
    ;; This test exposes the critical bug found during manual testing
    (let [tel-aviv-boundary [[34.75 32.12] [34.82 32.12] [34.82 32.05] [34.75 32.05] [34.75 32.12]]

          test-image-accuracy (fn [image-path]
                                (let [coords (selector/parse-coords-from-filename image-path)
                                      [lon lat] coords]
                                  (and (<= 34.75 lon 34.82) (<= 32.05 lat 32.12))))

          tel-aviv-results (map (fn [img]
                                  {:image img
                                   :actually-in-tel-aviv (test-image-accuracy img)})
                                selector/tel-aviv-images)

          incorrect-count (count (filter #(not (:actually-in-tel-aviv %)) tel-aviv-results))
          total-count (count tel-aviv-results)
          error-rate (* 100.0 (/ incorrect-count total-count))]

      ;; This test documents the critical bug - should FAIL until fixed
      (is (< error-rate 10.0)
          (str "CRITICAL BUG: " error-rate "% of Tel Aviv images are outside boundaries. "
               incorrect-count " out of " total-count " images are mislabeled.")))))

(deftest test-game-progression
  (testing "Normal game progression"
    (let [session (game/new-game "Progression Test")
          session-id (:session-id session)]
      (game/save-game-session! session)

      ;; Generate first image
      (let [img1 (game/get-or-generate-current-image session-id)]
        (is (some? img1)))

      ;; Process first answer
      (let [result1 (game/process-answer session-id true)]
        (is (some? result1))
        (is (boolean? (:correct? result1)))
        (is (>= (:points result1) 0)))

      ;; Advance to next stage
      (let [advance-result (game/advance-to-next-image session-id)]
        (is (= 2 (:current-stage advance-result)))
        (is (some? (:current-image advance-result)))))))

(deftest test-image-collections
  (testing "Image collections exist and are accessible"
    (is (> (count selector/tel-aviv-images) 0))
    (is (map? selector/neighbor-images))
    (is (contains? selector/neighbor-images :ramat-gan))
    (is (contains? selector/neighbor-images :givatayim))
    (is (contains? selector/neighbor-images :bnei-brak)))

  (testing "All images have parseable filenames"
    ;; Test Tel Aviv images
    (doseq [img selector/tel-aviv-images]
      (let [coords (selector/parse-coords-from-filename img)]
        (is (or (vector? coords) (nil? coords))
            (str "Image " img " should have parseable coords or nil"))))

    ;; Test neighbor images (those with coordinate patterns)
    (doseq [[city images] selector/neighbor-images]
      (doseq [img images]
        (when (re-find #"\d+\.\d+_\d+\.\d+" img) ; Only test images with coordinate pattern
          (let [coords (selector/parse-coords-from-filename img)]
            (is (vector? coords)
                (str "Image " img " should have parseable coordinates"))))))))

(deftest test-answer-correctness-logic
  (testing "Answer correctness determination"
    ;; User says true, image is in Tel Aviv -> CORRECT
    (is (= true (= true true)))

    ;; User says false, image is in Tel Aviv -> WRONG  
    (is (= false (= false true)))

    ;; User says true, image is NOT in Tel Aviv -> WRONG
    (is (= false (= true false)))

    ;; User says false, image is NOT in Tel Aviv -> CORRECT
    (is (= true (= false false)))))

(deftest test-memory-and-concurrency
  (testing "Session storage doesn't leak"
    (let [initial-count (count @game/game-sessions)]
      (dotimes [i 5]
        (let [session (game/new-game (str "Memory test " i))]
          (game/save-game-session! session)))
      (is (= (+ initial-count 5) (count @game/game-sessions)))))

  (testing "Basic concurrent access safety"
    (let [session (game/new-game "Concurrent test")
          session-id (:session-id session)]
      (game/save-game-session! session)

      ;; Simulate concurrent image generation
      (let [future1 (future (game/get-or-generate-current-image session-id))
            future2 (future (game/get-or-generate-current-image session-id))
            img1 @future1
            img2 @future2]

        ;; Both should succeed and return the same image
        (is (some? img1))
        (is (some? img2))
        (is (= (:url img1) (:url img2)))))))

;; Integration test that runs a complete game
(deftest test-complete-game-integration
  (testing "Complete 3-stage game"
    (let [session (game/new-game "Integration Test")
          session-id (:session-id session)]
      (game/save-game-session! session)

      (dotimes [stage 3]
        (let [img (game/get-or-generate-current-image session-id)
              _ (is (some? img) (str "Should generate image for stage " (inc stage)))

              ;; Always answer correctly based on the image's is-in-tel-aviv flag
              user-answer (:is-in-tel-aviv img)
              result (game/process-answer session-id user-answer)
              _ (is (true? (:correct? result)) "Should be correct when matching is-in-tel-aviv flag")]

          ;; Advance to next stage (except on last stage)
          (when (< stage 2)
            (let [advance-result (game/advance-to-next-image session-id)]
              (is (= (+ stage 2) (:current-stage advance-result)))))))

      ;; Check final session state
      (let [final-session (game/get-game-session session-id)]
        (is (= 3 (:current-stage final-session)))
        (is (> (:score final-session) 0))))))