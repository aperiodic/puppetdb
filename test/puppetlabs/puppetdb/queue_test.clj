(ns puppetlabs.puppetdb.queue-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.queue :refer :all]
            [clj-time.core :as t :refer [days ago now seconds]]
            [clj-time.coerce :as tcoerce]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.constants :as constants]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [puppetlabs.puppetdb.testutils :as tu]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.testutils.nio :as nio]
            [puppetlabs.puppetdb.utils :refer [utf8-length]]))

(deftest test-sanitize-certname
  (are [raw sanitized] (= sanitized (sanitize-certname raw))
    "foo/bar" "foo-bar"
    "foo/bar/baz" "foo-bar-baz"
    "/foo/" "-foo-"
    "/foo//bar///baz/" "-foo--bar---baz-")
  (doseq [bad-char (conj constants/filename-forbidden-characters \_)]
    (is (= "sanitize-me" (sanitize-certname (format "sanitize%cme" bad-char))))))

(deftest test-metadata-str
  (let [recvd (now)
        recvd-long (tcoerce/to-long recvd)
        cmd "replace facts"
        cmd-ver 4]

    (testing "certnames are sanitized"
      (let [cname "foo_bar/baz"
            safe-cname "foo-bar-baz"
            cname-hash (kitchensink/utf8-string->sha1 cname)]
        (is (= (format "%d_%s_%d_%s_%s.json" recvd-long cmd cmd-ver safe-cname cname-hash)
               (metadata-str recvd cmd cmd-ver cname)))))

    (testing "long certnames are truncated"
      (let [long-cname (apply str "trol" (repeat 1000 "lo"))
            trunc-cname (subs long-cname 0 (truncated-certname-length recvd cmd cmd-ver))
            cname-hash (kitchensink/utf8-string->sha1 long-cname)]
        (is (= (format "%d_%s_%d_%s_%s.json" recvd-long cmd cmd-ver trunc-cname cname-hash)
               (metadata-str recvd cmd cmd-ver long-cname)))
        (is (<= (utf8-length (metadata-str recvd cmd cmd-ver long-cname)) 255))))

    (testing "multi-byte characters in UTF-8 are counted correctly"
      (let [cname-max-length (max-certname-length recvd cmd cmd-ver)
            disapproval-monster (apply str (repeat (inc (/ cname-max-length 4)) "ಠ_"))]
        (is (<= (utf8-length (metadata-str recvd cmd cmd-ver disapproval-monster)) 255))))

    (testing "sanitized certnames are truncated to leave room for hash"
      (let [cname-trunc-length (truncated-certname-length recvd cmd cmd-ver)
            tricky-cname (apply str "____" (repeat cname-trunc-length "o"))
            cname-hash (kitchensink/utf8-string->sha1 tricky-cname)
            trunc-cname (subs (sanitize-certname tricky-cname) 0 cname-trunc-length)]
        (is (= (format "%d_%s_%d_%s_%s.json" recvd-long cmd cmd-ver trunc-cname cname-hash)
               (metadata-str recvd cmd cmd-ver tricky-cname)))
        (is (<= (utf8-length (metadata-str recvd cmd cmd-ver tricky-cname)) 255))))

    (testing "short & safe certnames are preserved and the hash is omitted"
      (let [cname "bender.myowncasino.moon"]
        (is (= (format "%d_%s_%d_%s.json" recvd-long cmd cmd-ver cname)
               (metadata-str recvd cmd cmd-ver cname)))))))

(deftest test-metadata
  (tqueue/with-stockpile q
    (let [now (time/now)
          ;; Sleep to ensure the command has a different time
          _ (Thread/sleep 1)
          cmdref (store-command q "my command" 1 "foo.com" (-> "{\"message\": \"payload\"}"
                                                               (.getBytes "UTF-8")
                                                               java.io.ByteArrayInputStream.))
           command (cmdref->cmd q cmdref)]
      (is (= {:command "my command"
              :version 1
              :certname "foo.com"
              :payload {:message "payload"}}
             (select-keys command [:command :version :certname :payload])))
      (is (t/before? now (tcoerce/from-string (get-in command [:annotations :received])))))))

(deftest test-sorted-command-buffer
  (testing "newer catalogs/facts cause older catalogs to be deleted"
    (let [buff (sorted-command-buffer 4)
          c (async/chan buff)
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace facts"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-4 (map->CommandRef {:id 4
                                      :command "replace facts"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (is (= 0 (count buff)))

      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2
        foo-cmd-3
        foo-cmd-4)

      (is (not (async/offer! c "should not be added")))

      (is (= (assoc foo-cmd-1 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-2
             (async/<!! c)))
      (is (= (assoc foo-cmd-3 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-4
             (async/<!! c)))))

  (testing "a new catalog after the previous one was processed"
    (let [buff (sorted-command-buffer 1)
          c (async/chan buff)
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (is (= 0 (count buff)))

      (is (async/offer! c foo-cmd-1))
      (is (= foo-cmd-1 (async/<!! c)))

      (is (async/offer! c foo-cmd-2))
      (is (= foo-cmd-2 (async/<!! c)))))

  (testing "multiple older catalogs all get marged as deleted"
    (let [c (async/chan (sorted-command-buffer 3))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2
        foo-cmd-3)

      (is (= (assoc foo-cmd-1 :delete? true)
             (async/<!! c)))
      (is (= (assoc foo-cmd-2 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-3
             (async/<!! c)))))

  (testing "multiple reports should remain unchanged"
    (let [c (async/chan (sorted-command-buffer 10))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "store report"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "store report"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2)

      (is (= foo-cmd-1 (async/<!! c)))
      (is (= foo-cmd-2 (async/<!! c))))))

(deftest test-loading-existing-messages
  (testing "loading existing messages into a channel"
    (nio/call-with-temp-dir-path
     (get-path "target")
     (str *ns*)
     (fn [temp-path]
       (let [[q load-messages] (create-or-open-stockpile temp-path)]

         (is (nil? load-messages))
         (tqueue/store-command q "replace catalog" 1 "foo1" {:message "payload 1"})
         (tqueue/store-command q "replace catalog" 1 "foo2" {:message "payload 2"})
         (tqueue/store-command q "replace catalog" 1 "foo3" {:message "payload 3"})
         (tqueue/store-command q "replace catalog" 1 "foo4" {:message "payload 4"}))

       (let [[q load-messages] (create-or-open-stockpile temp-path)
             command-chan (async/chan 4)
             cc (tu/call-counter)]

         (load-messages command-chan cc)

         (is (= #{"foo1" "foo2" "foo3" "foo4"}
                (set (map :certname (repeatedly 4 #(async/poll! command-chan))))))

         (is (= 4 (tu/times-called cc)))
         (is (= (repeat 4 ["replace catalog" 1])
                (tu/args-supplied cc)))

         (is (nil? (async/poll! command-chan)))))))

  (testing "adding new commands while loading existing commands"
    (nio/call-with-temp-dir-path
     (get-path "target")
     (str *ns*)
     (fn [temp-path]
       (let [[q load-messages] (create-or-open-stockpile temp-path)]

         (is (nil? load-messages))
         (tqueue/store-command q "replace catalog" 1 "foo1" {:message "payload 1"})
         (tqueue/store-command q "replace catalog" 1 "foo2" {:message "payload 2"}))

       (let [[q load-messages] (create-or-open-stockpile temp-path)
             command-chan (async/chan 4)
             cc (tu/call-counter)]

         (tqueue/store-command q "replace catalog" 1 "foo3" {:message "payload 3"})
         (tqueue/store-command q "replace catalog" 1 "foo4" {:message "payload 4"})

         (load-messages command-chan cc)

         (is (= 2 (tu/times-called cc)))
         (is (= (repeat 2 ["replace catalog" 1])
                (tu/args-supplied cc)))

         (is (= #{"foo1" "foo2"}
                (set (map :certname (repeatedly 2 #(async/poll! command-chan))))))

         (is (not (async/poll! command-chan))))))))
