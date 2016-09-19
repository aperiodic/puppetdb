(ns puppetlabs.puppetdb.utils-test
  (:require [puppetlabs.puppetdb.utils :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as cct]))

(deftest test-println-err
  (is (= "foo\n"
         (tu/with-err-str (println-err "foo"))))

  (is (= "foo bar\n"
         (tu/with-err-str (println-err "foo" "bar")))))

(def jdk-1-6-version "1.6.0_45")

(def jdk-1-7-version "1.7.0_45")

(def unsupported-regex
  (re-pattern (format ".*JDK 1.6 is no longer supported. PuppetDB requires JDK 1.7\\+, currently running.*%s" jdk-1-6-version)))

(deftest test-jdk6?
  (with-redefs [kitchensink/java-version jdk-1-6-version]
    (is (true? (jdk6?))))

  (with-redefs [kitchensink/java-version jdk-1-7-version]
    (is (false? (jdk6?)))))

(deftest unsupported-jdk-failing
  (testing "1.6 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-6-version]
      (pllog/with-log-output log
        (let [fail? (atom false)
              result (tu/with-err-str (fail-unsupported-jdk #(reset! fail? true)))
              [[category level _ msg]] @log]
          (is (= "puppetlabs.puppetdb.utils" category))
          (is (= :error level))
          (is (re-find unsupported-regex msg))
          (is (re-find unsupported-regex result))
          (is (true? @fail?))))))

  (testing "1.7 jdk version"
    (with-redefs [kitchensink/java-version jdk-1-7-version]
      (pllog/with-log-output log
        (let [fail? (atom false)
              result (tu/with-err-str (fail-unsupported-jdk #(reset! fail? true)))]
          (is (empty? @log))
          (is (str/blank? result))
          (is (false? @fail?)))))))

(deftest test-assoc-when
  (is (= {:a 1 :b 2}
         (assoc-when {:a 1 :b 2} :b 100)))
  (is (= {:a 1 :b 100}
         (assoc-when {:a 1} :b 100)))
  (is (= {:b 100}
         (assoc-when nil :b 100)))
  (is (= {:b 100}
         (assoc-when {} :b 100)))
  (is (= {:a 1 :b 2 :c  3}
         (assoc-when {:a 1} :b 2 :c 3))))

(deftest stringify-keys-test
  (let [sample-data1 {"foo/bar" "data" "key with space" {"child/foo" "baz"}}
        sample-data2 {:foo/bar "data" :fuz/bash "data2"}
        keys         (walk/keywordize-keys sample-data1)]
    (is (= sample-data1 (stringify-keys keys)))
    (is (= {"foo/bar" "data" "fuz/bash" "data2"} (stringify-keys sample-data2)))))

(deftest describe-bad-base-url-behavior
  (is (not (describe-bad-base-url {:protocol "http" :host "xy" :port 0})))
  (is (string? (describe-bad-base-url {:protocol "http" :host "x:y" :port 0}))))

(deftest test-regex-quote
  (is (thrown? IllegalArgumentException (regex-quote "Rob's \\Ecommand")))
  (let [special-chars "$.^[)"]
    (is (= special-chars (-> (regex-quote special-chars)
                             re-pattern
                             (re-find (format "fo*%s?!" special-chars)))))))

(deftest test-match-any-of
  (let [special-chars [\$ "." \] "()"]
        match-special (re-pattern (match-any-of special-chars))]
    (doseq [special-char special-chars]
      (is (= (str special-char) (-> (re-find match-special (format "con%stext" special-char))
                                    first))))))

(def dash-keyword-generator
  (gen/fmap (comp keyword #(str/join "-" %))
            (gen/not-empty (gen/vector gen/string-alpha-numeric))))

(def underscore-keyword-generator
  (gen/fmap (comp keyword #(str/join "_" %))
            (gen/not-empty (gen/vector gen/string-alpha-numeric))))

(cct/defspec test-dash-conversions
  50
  (prop/for-all [w (gen/map dash-keyword-generator gen/any)]
                (= w
                   (underscore->dash-keys (dash->underscore-keys w)))))

(cct/defspec test-underscore-conversions
  50
  (prop/for-all [w (gen/map underscore-keyword-generator gen/any)]
                (= w
                   (dash->underscore-keys (underscore->dash-keys w)))))

(deftest test-utf8-truncate
  (is (= "ಠ" (utf8-truncate "ಠ_ಠ" 3)))
  (is (= "ಠ_" (utf8-truncate "ಠ_ಠ" 4)))
  (is (= "ಠ_" (utf8-truncate "ಠ_ಠ" 5)))
  (is (= "ಠ_" (utf8-truncate "ಠ_ಠ" 6)))
  (is (= "ಠ_ಠ" (utf8-truncate "ಠ_ಠ" 7)))
  (is (= "ಠ_ಠ" (utf8-truncate "ಠ_ಠಠ" 8)))

  (testing "when string starts with multi-byte character and is truncated to fewer bytes, yields empty string"
    (is (= "" (utf8-truncate "ಠ_ಠ" 1)))
    (is (= "" (utf8-truncate "ಠ_ಠ" 2))))

  (testing "truncation doesn't add extra bytes"
    (is (= "foo" (utf8-truncate "foo" 256)))))
