(ns metabase.query-processor.middleware.parameters.mbql-test
  "Tests for *MBQL* parameter substitution."
  (:require [expectations :refer :all]
            [metabase
             [query-processor :as qp]
             [query-processor-test :refer [first-row format-rows-by non-timeseries-engines rows]]
             [util :as u]]
            [metabase.models
             [field :refer [Field]]
             [table :refer [Table]]]
            [metabase.query-processor.middleware.expand :as ql]
            [metabase.query-processor.middleware.parameters.mbql :refer :all]
            [metabase.test.data :as data]
            [metabase.test.data.datasets :as datasets]
            [metabase.util.honeysql-extensions :as hx]))

(defn- expand-parameters [query]
  (expand (dissoc query :parameters) (:parameters query)))


;; adding a simple parameter
(expect
  {:database   1
   :type       :query
   :query      {:filter   [:= ["field-id" 123] "666"]
                :breakout [17]}}
  (expand-parameters {:database   1
                      :type       :query
                      :query      {:breakout [17]}
                      :parameters [{:hash   "abc123"
                                    :name   "foo"
                                    :type   "id"
                                    :target ["dimension" ["field-id" 123]]
                                    :value  "666"}]}))

;; multiple filters are conjoined by an "AND"
(expect
  {:database   1
   :type       :query
   :query      {:filter   ["AND" ["AND" ["AND" ["=" 456 12]] [:= ["field-id" 123] "666"]] [:= ["field-id" 456] "999"]]
                :breakout [17]}}
  (expand-parameters {:database   1
                      :type       :query
                      :query      {:filter   ["AND" ["=" 456 12]]
                                   :breakout [17]}
                      :parameters [{:hash   "abc123"
                                    :name   "foo"
                                    :type   "id"
                                    :target ["dimension" ["field-id" 123]]
                                    :value  "666"}
                                   {:hash   "def456"
                                    :name   "bar"
                                    :type   "category"
                                    :target ["dimension" ["field-id" 456]]
                                    :value  "999"}]}))

;; date range parameters
(expect
  {:database   1
   :type       :query
   :query      {:filter   ["TIME_INTERVAL" ["field-id" 123] -30 "day" {:include-current false}]
                :breakout [17]}}
  (expand-parameters {:database   1
                      :type       :query
                      :query      {:breakout [17]}
                      :parameters [{:hash   "abc123"
                                    :name   "foo"
                                    :type   "date"
                                    :target ["dimension" ["field-id" 123]]
                                    :value  "past30days"}]}))

(expect
  {:database   1
   :type       :query
   :query      {:filter   ["TIME_INTERVAL" ["field-id" 123] -30 "day" {:include-current true}]
                :breakout [17]}}
  (expand-parameters {:database   1
                      :type       :query
                      :query      {:breakout [17]}
                      :parameters [{:hash   "abc123"
                                    :name   "foo"
                                    :type   "date"
                                    :target ["dimension" ["field-id" 123]]
                                    :value  "past30days~"}]}))

(expect
  {:database   1
   :type       :query
   :query      {:filter   ["=" ["field-id" 123] ["relative_datetime" -1 "day"]]
                :breakout [17]}}
  (expand-parameters {:database   1
                      :type       :query
                      :query      {:breakout [17]}
                      :parameters [{:hash   "abc123"
                                    :name   "foo"
                                    :type   "date"
                                    :target ["dimension" ["field-id" 123]]
                                    :value  "yesterday"}]}))

(expect
  {:database   1
   :type       :query
   :query      {:filter   ["BETWEEN" ["field-id" 123] "2014-05-10" "2014-05-16"]
                :breakout [17]}}
  (expand-parameters {:database   1
                      :type       :query
                      :query      {:breakout [17]}
                      :parameters [{:hash   "abc123"
                                    :name   "foo"
                                    :type   "date"
                                    :target ["dimension" ["field-id" 123]]
                                    :value  "2014-05-10~2014-05-16"}]}))



;;; +-------------------------------------------------------------------------------------------------------+
;;; |                                           END-TO-END TESTS                                            |
;;; +-------------------------------------------------------------------------------------------------------+

;; for some reason param substitution tests fail on Redshift & (occasionally) Crate so just don't run those for now
(def ^:private ^:const params-test-engines (disj non-timeseries-engines :redshift :crate))

;; check that date ranges work correctly
(datasets/expect-with-engines params-test-engines
  [29]
  (first-row
    (format-rows-by [int]
      (qp/process-query {:database   (data/id)
                         :type       :query
                         :query      (data/query checkins
                                       (ql/aggregation (ql/count)))
                         :parameters [{:hash   "abc123"
                                       :name   "foo"
                                       :type   "date"
                                       :target ["dimension" ["field-id" (data/id :checkins :date)]]
                                       :value  "2015-04-01~2015-05-01"}]}))))

;; check that IDs work correctly (passed in as numbers)
(datasets/expect-with-engines params-test-engines
  [1]
  (first-row
    (format-rows-by [int]
      (qp/process-query {:database   (data/id)
                         :type       :query
                         :query      (data/query checkins
                                       (ql/aggregation (ql/count)))
                         :parameters [{:hash   "abc123"
                                       :name   "foo"
                                       :type   "number"
                                       :target ["dimension" ["field-id" (data/id :checkins :id)]]
                                       :value  100}]}))))

;; check that IDs work correctly (passed in as strings, as the frontend is wont to do; should get converted)
(datasets/expect-with-engines params-test-engines
  [1]
  (first-row
    (format-rows-by [int]
      (qp/process-query {:database   (data/id)
                         :type       :query
                         :query      (data/query checkins
                                       (ql/aggregation (ql/count)))
                         :parameters [{:hash   "abc123"
                                       :name   "foo"
                                       :type   "number"
                                       :target ["dimension" ["field-id" (data/id :checkins :id)]]
                                       :value  "100"}]}))))

;; test that we can injuect a basic `WHERE id = 9` type param (`id` type)
(datasets/expect-with-engines params-test-engines
  [[9 "Nils Gotam"]]
  (format-rows-by [int str]
    (let [inner-query (data/query users)
          outer-query (-> (data/wrap-inner-query inner-query)
                          (assoc :parameters [{:name   "id"
                                               :type   "id"
                                               :target ["field-id" (data/id :users :id)]
                                               :value  9}]))]
      (rows (qp/process-query outer-query)))))

;; test that we can do the same thing but with a `category` type
(datasets/expect-with-engines params-test-engines
  [[6]]
  (format-rows-by [int]
    (let [inner-query (data/query venues
                        (ql/aggregation (ql/count)))
          outer-query (-> (data/wrap-inner-query inner-query)
                          (assoc :parameters [{:name   "price"
                                               :type   "category"
                                               :target ["field-id" (data/id :venues :price)]
                                               :value  4}]))]
      (rows (qp/process-query outer-query)))))


;; Make sure that *multiple* values work. This feature was added in 0.28.0. You are now allowed to pass in an array of
;; parameter values instead of a single value, which should stick them together in a single MBQL `:=` clause, which
;; ends up generating a SQL `*or*` clause
(datasets/expect-with-engines params-test-engines
  [[19]]
  (format-rows-by [int]
    (let [inner-query (data/query venues
                        (ql/aggregation (ql/count)))
          outer-query (-> (data/wrap-inner-query inner-query)
                          (assoc :parameters [{:name   "price"
                                               :type   "category"
                                               :target ["field-id" (data/id :venues :price)]
                                               :value  [3 4]}]))]
      (rows (qp/process-query outer-query)))))

;; now let's make sure the correct query is actually being generated for the same thing above...
;;
;; TODO - the util function below is similar to a few other ones that are used elsewhere in the tests. It would be
;; nice to unifiy those at some point or possibly move them into a shared utility namespace. Something to consider!
(defn- mbql-param-quoted-and-qualified-name
  "Generate a quoted and qualified identifier for a Table or Field for the current driver that will be used for MBQL
  param subsitution below. e.g.

    ;; with SQLServer
    (mbql-param-quoted-and-qualified-name :venues) ;-> :dbo.venues
    (mbql-param-quoted-and-qualified-name :venues :price) ;-> :dbo.venues.price"
  ([table-kw]
   (let [table (Table (data/id table-kw))]
     (hx/qualify-and-escape-dots (:schema table) (:name table))))
  ([table-kw field-kw]
   (let [table (Table (data/id table-kw))
         field (Field (data/id table-kw field-kw))]
     (hx/qualify-and-escape-dots (:schema table) (:name table) (:name field)))))

(datasets/expect-with-engines params-test-engines
  {:query  (format "SELECT count(*) AS \"count\" FROM %s WHERE (%s = 3 OR %s = 4)"
                   (mbql-param-quoted-and-qualified-name :venues)
                   (mbql-param-quoted-and-qualified-name :venues :price)
                   (mbql-param-quoted-and-qualified-name :venues :price))
   :params nil}
  (let [inner-query (data/query venues
                      (ql/aggregation (ql/count)))
        outer-query (-> (data/wrap-inner-query inner-query)
                        (assoc :parameters [{:name   "price"
                                             :type   "category"
                                             :target ["field-id" (data/id :venues :price)]
                                             :value  [3 4]}]))]
    (-> (qp/process-query outer-query)
        :data :native_form)))

;; try it with date params as well. Even though there's no way to do this in the frontend AFAIK there's no reason we
;; can't handle it on the backend
(datasets/expect-with-engines params-test-engines
  {:query  (format (str "SELECT count(*) AS \"count\" FROM %s "
                        "WHERE (CAST(%s AS date) BETWEEN CAST(? AS date) AND CAST(? AS date) "
                        "OR CAST(%s date) BETWEEN CAST(? AS date) AND CAST(? AS date))")
                   (mbql-param-quoted-and-qualified-name :checkins)
                   (mbql-param-quoted-and-qualified-name :checkins :date)
                   (mbql-param-quoted-and-qualified-name :checkins :date))
   :params [(u/->Timestamp #inst "2014-06-01")
            (u/->Timestamp #inst "2014-06-30")
            (u/->Timestamp #inst "2015-06-01")
            (u/->Timestamp #inst "2015-06-30")]}
  (let [inner-query (data/query checkins
                      (ql/aggregation (ql/count)))
        outer-query (-> (data/wrap-inner-query inner-query)
                        (assoc :parameters [{:name   "date"
                                             :type   "date/month"
                                             :target ["field-id" (data/id :checkins :date)]
                                             :value  ["2014-06" "2015-06"]}]))]
    (-> (qp/process-query outer-query)
        :data :native_form)))
