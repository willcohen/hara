(ns hara.concurrent.ova
  (:require [clojure.set :as set]
            [hara.common.state :as state]
            [hara.common.watch :as watch]
            [hara.common.error :refer [error suppress]]
            [hara.common.hash :refer [hash-label]]
            [hara.expression.shorthand :refer [get-> check-> check?->]]
            [hara.function.args :as args]))

(defn- ova-state
  []
  (do {::data      (ref [])
       ::watches   (atom {})}))

(defn- internal-watch-fn [ova]
  (fn [k & args]
    (doseq [w (hara.protocol.watch/-list-watch ova {:type :elements})]
      (let [wk (first w)
            wf (second w)]
        (apply wf wk ova args)))))

(defn- add-internal-watch [ova irf]
  (let [k  (-> ova hash-label keyword)
        f  (internal-watch-fn ova)]
    (add-watch irf k f)))

(defn- remove-internal-watch [ova irf]
  (let [k (-> ova hash-label keyword)]
    (remove-watch irf k)))

(defn- delete-internal-watches [ova idx]
  (map-indexed (fn [i obj]
                 (if-not (idx i)
                   obj
                   (do (remove-internal-watch ova obj)
                       obj)))
               @ova)
  ova)

(defn get-filtered
  "gets the first element in the ova that matches the selector:
 
   (def o (ova [{:id :1 :val 1} {:id :2 :val 1}]))
   
   (get-filtered o :1 nil nil)
   => {:val 1, :id :1}
 
   (get-filtered o :2 nil nil)
   => {:val 1, :id :2}
 
   (get-filtered o :3 nil :not-found)
   => :not-found"
  {:added "2.1"}
  [ova k sel nv]
  (cond (and (nil? sel) (integer? k))
        (nth ova k nv)

        :else
        (let [res (->> (map deref @ova)
                       (filter (fn [m] (check?-> m [(or sel :id) k])))
                       first)]
          (or res nv))))

(defn- standardise-opts [opts]
  (cond (keyword? opts) {:type opts}
        :else opts))

(deftype Ova [state]

  hara.protocol.watch/IWatch
  (-add-watch    [obj k f opts]
    (let [opts (standardise-opts opts)]
      (cond (or (= (:type opts) :ova))
            (do (add-watch (::data state) k
                           (watch/process-options opts f))
                (.getWatches ^clojure.lang.IRef (::data state)))


            :else
            (swap! (::watches state) assoc k
                   (watch/process-options (assoc opts :args 5) f)))))

  (-remove-watch [ova k opts]
    (let [opts (standardise-opts opts)]
      (cond (= (:type opts) :ova)
            (remove-watch (::data state) k)

            :else
            (swap! (::watches state) dissoc k))))

  (-list-watch [obj opts]
    (let [opts (standardise-opts opts)]
      (cond (= (:type opts) :ova)
            (.getWatches ^clojure.lang.IRef (::data state))

            :else
            (deref (::watches state)))))

  hara.protocol.state/IStateGet
  (-get-state [ova opts]
    (::data state))

  hara.protocol.state/IStateSet
  (-empty-state [ova opts]
    (doseq [rf @(::data state)]
      (remove-internal-watch ova rf))
    (ref-set (::data state) [])
    ova)

  (-set-state [ova opts arr]
    (state/empty ova)
    (doseq [e arr]
      (conj! ova e)))

  clojure.lang.IDeref
  (deref [ova] @(::data state))

  clojure.lang.IRef
  (setValidator [ova vf]
    (.setValidator ^clojure.lang.IRef (::data state) vf))

  (getValidator [ova]
    (.getValidator ^clojure.lang.IRef (::data state)))

  (getWatches [ova]
    (.getWatches ^clojure.lang.IRef (::data state)))

  (addWatch [ova key callback]
    (add-watch (::data state) key callback))

  (removeWatch [ova key]
    (remove-watch (::data state) key))

  clojure.lang.ITransientCollection
  (conj [ova v]
    (let [ev (ref v)]
      (add-internal-watch ova ev)
      (alter (::data state) conj ev))
    ova)

  (persistent [ova]
    (mapv deref @(::data state)))

  clojure.lang.ITransientAssociative
  (assoc [ova k v]
    (if-let [pv (get @ova k)]
      (ref-set pv v)
      (let [ev (ref v)]
        (add-internal-watch ova ev)
        (alter (::data state) assoc k ev)))
    ova)

  clojure.lang.ITransientVector
  (assocN [ova i v] (assoc ova i v))

  (pop [ova]
    (if-let [lv (last @ova)]
      (remove-internal-watch ova lv))
    (alter (::data state) pop)
    ova)

  clojure.lang.ILookup
  (valAt [ova k]
    (get-filtered ova k nil nil))

  (valAt [ova k not-found]
    (get-filtered ova k nil not-found))

  clojure.lang.Indexed
  (nth [ova i]
    (nth ova i nil))

  (nth [ova i not-found]
     (if-let [entry (nth @ova i)]
       @entry not-found))

  clojure.lang.Counted
  (count [ova] (count @ova))

  clojure.lang.Seqable
  (seq [ova]
    (let [res (map deref (seq @ova))]
      (if-not (empty? res) res)))

  clojure.lang.IFn
  (invoke [ova k] (get-filtered ova k nil nil))
  (invoke [ova sel k] (get-filtered ova k sel nil))

  java.lang.Object
  (toString [ova]
    (str "#ova " (persistent! ova))))

(defmethod print-method Ova
  [v ^java.io.Writer w]
  (.write w (str v)))

(defn ova
  "constructs an instance of an ova
 
   (ova []) ;=> #ova []
 
   (ova [1 2 3]) ;=>  #ova [1 2 3]
 
   (<< (ova [{:id :1} {:id :2}]))
   => [{:id :1} {:id :2}]"
  {:added "2.1"}
  ([] (Ova. (ova-state)))
  ([coll]
     (let [ova (Ova. (ova-state))]
       (dosync
        (state/set ova coll))
       ova)))

(defn ova?
  "checks if an object is an ova instance
 
   (ova? (ova [1 2 3]))
   => true"
  {:added "2.4"}
  [x]
  (instance? Ova x))

(defn concat!
  "works like `concat`, allows both array and ova inputs
 
   (<< (concat! (ova [{:id :1 :val 1}
                      {:id :2 :val 1}])
                (ova [{:id :3 :val 2}])
                [{:id :4 :val 2}]))
   => [{:val 1, :id :1}
       {:val 1, :id :2}
      {:val 2, :id :3}
       {:val 2, :id :4}]"
  {:added "2.1"}
  [ova es & more]
  (let [_ (doseq [e es] (conj! ova e))]
    (if (seq more)
      (apply concat! ova more))
    ova))

(defn append!
  "like `conj!` but appends multiple array elements to the ova
 
   (-> (ova [{:id :1 :val 1}])
       (append! {:id :2 :val 1}
                {:id :3 :val 2})
       (<<))
   => [{:id :1 :val 1}
       {:id :2 :val 1}
      {:id :3 :val 2}]"
  {:added "2.1"}
  [ova & es]
  (concat! ova es))

(defn empty!
  "empties an existing ova
 
   (-> (ova [1 2 3 4 5])
       (empty!)
       (<<))
   => []"
  {:added "2.1"}
  [ova]
  (watch/clear ova {:type :ova})
  (watch/clear ova)
  (state/empty ova)
  ova)

(defn init!
  "sets elements within an ova
 
   (def o (ova []))
   (->> (init! o [{:id :1 :val 1} {:id :2 :val 1}])
        (dosync)
        (<<))
   => [{:val 1, :id :1} {:val 1, :id :2}]"
  {:added "2.1"}
  ([ova]
   (empty! ova))
  ([ova coll]
   (empty! ova)
   (state/set ova coll)
   ova))

(defn indices
  "instead of data, outputs the matching indices
       
   (def o (ova [{:id :1 :val 1} {:id :2 :val 1}
                {:id :3 :val 2} {:id :4 :val 2}]))
   
   (indices o)
   => [0 1 2 3]
 
   (indices o 0)
   => [0]
 
   (indices o [:val 1])
   => [0 1]
 
   (indices o [:val even?])
   => [2 3]
   
   (indices o [:val even?
               '(:id (name) (bigint)) odd?])
   => [2]
 
   (indices o #{4})
   => []
   
   (indices o [:id :1])
   => [0]"
  {:added "2.1"}
  ([ova] (-> (count ova) range vec))
  ([ova pchk]
     (cond
      (number? pchk)
      (if (suppress (get ova pchk)) (list pchk) ())

      (set? pchk)
      (mapcat #(indices ova %) pchk)

      :else
      (filter (comp not nil?)
              (map-indexed (fn [i obj]
                             (check?-> obj pchk i))
                           ova)))))

(defn selectv
  "grabs the selected ova entries as vector
 
   (def o (ova [{:id :1 :val 1} {:id :2 :val 1}
                {:id :3 :val 2} {:id :4 :val 2}]))
   
   (selectv o)              ;; no filters
   => [{:id :1, :val 1}  
       {:id :2, :val 1}
       {:id :3, :val 2}
       {:id :4, :val 2}]
   
   (selectv o 0)            ;; by index
   => [{:id :1 :val 1}] 
 
   (selectv o [:val even?])    ;; by shorthand function
   => [{:id :3 :val 2}
       {:id :4 :val 2}]
   
   (selectv o [:id '((name)    ;; by shorthand expression
                     (bigint)
                     (odd?))])
   => [{:id :1 :val 1}
       {:id :3 :val 2}]"
  {:added "2.1"}
  ([ova]
      (persistent! ova))
  ([ova pchk]
    (cond (number? pchk)
          (if-let [val (suppress (get ova pchk))]
            (list val) ())

          (set? pchk) (mapcat #(selectv ova %) pchk)

          :else (filter
                 (fn [obj] (check?-> obj pchk obj))
                 ova))))

(defn select
  "grabs the selected ova entries as a set of values
 
   (def o (ova [{:id :1 :val 1} {:id :2 :val 1}
                {:id :3 :val 2} {:id :4 :val 2}]))
   
   (select o)              ;; no filters
   => #{{:id :1, :val 1}  
        {:id :2, :val 1}
        {:id :3, :val 2}
        {:id :4, :val 2}}
   
   (select o 0)            ;; by index
   => #{{:id :1 :val 1}} 
 
   (select o #{1 2})       ;; by indices
   => #{{:id :2 :val 1}
        {:id :3 :val 2}}
 
   (select o #(even? (:val %))) ;; by function
   => #{{:id :3 :val 2}
        {:id :4 :val 2}}
 
   (select o [:val 1])        ;; by shorthand value
   => #{{:id :1 :val 1}
        {:id :2 :val 1}}
 
   (select o [:val even?])    ;; by shorthand function
   => #{{:id :3 :val 2}
        {:id :4 :val 2}}
 
   (select o #{[:id :1]       ;; or selection
               [:val 2]})
   => #{{:id :1 :val 1}
        {:id :3 :val 2}
        {:id :4 :val 2}}
   
   (select o [:id '((name)    ;; by shorthand expression
                    (bigint)
                    (odd?))])
   => #{{:id :1 :val 1}
        {:id :3 :val 2}}"
  {:added "2.1"}
  ([ova] (set (selectv ova)))
  ([ova pchk]
     (set (selectv ova pchk))))

(defn has?
  "checks that the ova contains elements matching a selector
 
   (def o (ova [{:id :1 :val 1} {:id :2 :val 1}
                {:id :3 :val 2} {:id :4 :val 2}]))
   
   (has? o)
   => true
 
   (has? o 0)
   => true
 
   (has? o -1)
   => false
 
   (has? o [:id '((name)
                  (bigint)
                  (odd?))])
   => true"
  {:added "2.1"}
  ([ova]
     (-> (select ova) empty? not))
  ([ova pchk]
      (-> (select ova pchk) empty? not)))

(defn map!
  "applies a function on the ova with relevent arguments
 
   (-> (ova [{:id :1} {:id :2}])
       (map! assoc :val 1)
       (<<))
   => [{:val 1, :id :1}
       {:val 1, :id :2}]"
  {:added "2.1"}
  [ova f & args]
  (doseq [rf @ova]
    (apply alter rf f args))
  ova)

(defn map-indexed!
  "applies a function that taking the data index as well as the data
   to all elements of the ova
 
   (-> (ova [{:id :1} {:id :2}])
       (map-indexed! (fn [i m]
                       (assoc m :val i)))
       (<<))
   => [{:val 0, :id :1}
      {:val 1, :id :2}]"
  {:added "2.1"}
  [ova f]
  (doseq [i (range (count ova))]
    (alter (nth @ova i) #(f i %) ))
  ova)

(defn smap!
  "applies a function to only selected elements of the array
 
   (-> (ova [{:id :1 :val 1}
             {:id :2 :val 1}
             {:id :3 :val 2}
             {:id :4 :val 2}])
       (smap! [:val 1]
              update-in [:val] #(+ % 100))
      (<<))
   => [{:id :1, :val 101}
       {:id :2, :val 101}
       {:id :3, :val 2}
       {:id :4, :val 2}]"
  {:added "2.1"}
  [ova pchk f & args]
  (let [idx (indices ova pchk)]
    (doseq [i idx]
      (apply alter (nth @ova i) f args)))
  ova)

(defn smap-indexed!
  "applies a function that taking the data index as well as the data
   to selected elements of the ova
 
   (-> (ova [{:id :1 :val 1}
             {:id :2 :val 1}
             {:id :3 :val 2}
             {:id :4 :val 2}])
       (smap-indexed! [:val 1]
                     (fn [i m]
                        (update-in m [:val] #(+ i 100 %))))
       (<<))
   => [{:id :1, :val 101}
       {:id :2, :val 102}
       {:id :3, :val 2}
       {:id :4, :val 2}]"
  {:added "2.1"}
  [ova pchk f]
  (let [idx (indices ova pchk)]
    (doseq [i idx]
      (alter (nth @ova i) #(f i %))))
  ova)

(defn insert-fn
  ""
  [v val & [i]]
  (if (nil? i)
    (conj v val)
    (vec (clojure.core/concat (conj (subvec v 0 i) val)
                              (subvec v i)))))

(defn insert!
  "inserts data at either the end of the ova or when given an index
 
   (-> (ova (range 5))
       (insert! 6)
       (<<))
   => [0 1 2 3 4 6]
 
   (-> (ova (range 5))
       (insert! 6)
       (insert! 5 5)
       (<<))
   => [0 1 2 3 4 5 6]"
  {:added "2.1"}
  [ova val & [i]]
  (let [rf (ref val)]
    (add-internal-watch ova rf)
    (alter (state/get ova) insert-fn rf i))
  ova)

(defn sort!
  "sorts all data in the ova using a comparator function
 
   (-> (ova [2 1 3 4 0])
       (sort! >)
       (<<))
   => [4 3 2 1 0]
 
   (-> (ova [2 1 3 4 0])
       (sort! <)
       (<<))
   => [0 1 2 3 4]"
  {:added "2.1"}
  ([ova] (sort! ova compare))
  ([ova comp]
     (alter (state/get ova)
            (fn [state]
              (->> state
                   (sort (fn [x y]
                          (comp @x @y)))
                   vec)))
     ova)
  ([ova sel comp]
     (alter (state/get ova)
            (fn [state]
              (->> state
                   (sort (fn [x y]
                           (comp (get-> @x sel) (get-> @y sel))))
                   vec)))
     ova))

(defn reverse!
  "reverses the order of elements in the ova
 
   (-> (ova (range 5))
       (reverse!)
       (<<))
   => [4 3 2 1 0]"
  {:added "2.1"}
  [ova]
  (alter (state/get ova) (comp vec reverse))
  ova)

(defn- delete-internal-objs [ova indices]
  (->> ova
       (map-indexed (fn [i obj] (if-not (indices i) obj)))
       (filter (comp not nil?))
       vec))

(defn delete-indices
  ""
  [ova idx]
  (delete-internal-watches ova idx)
  (alter (state/get ova) delete-internal-objs idx)
  ova)

(defn remove!
  "removes data from the ova that matches a selector
 
   (-> (ova (range 10))
       (remove! odd?)
       (<<))
   => [0 2 4 6 8]
   
   (-> (ova (range 10))
       (remove! #{'(< 3) '(> 6)})
       (<<))
   => [3 4 5 6]"
  {:added "2.1"}
  [ova pchk]
  (let [idx (set (indices ova pchk))]
    (delete-indices ova idx))
  ova)

(defn filter!
  "keep only elements that matches the selector
 
   (-> (ova [0 1 2 3 4 5 6 7 8 9])
       (filter! #{'(< 3) '(> 6)})
       (<<))
   => [0 1 2 7 8 9]"
  {:added "2.1"}
  [ova pchk]
  (let [idx (set/difference
             (set (range (count ova)))
             (set (indices ova pchk)))]
    (delete-indices ova idx))
  ova)

(defn clone
  "creates an exact copy of the ova, including its watches
 
   (def o (ova (range 10)))
   (watch/set o {:a (fn [_ _ _ _ _])})
   
   (def other (clone o))
   
   (<< other) => (<< o)
   (watch/list other) => (just {:a fn?})"
  {:added "2.1"}
  [old]
  (let [ova (ova old)]
    (watch/copy ova old)
    (watch/copy ova old :ova)
    ova))

(defn split
  "splits an ova into two based on a predicate
   
   (def o (ova (range 10)))
   (def sp (dosync (split o #{'(< 3) '(> 6)})))
 
   (persistent! (sp true))  => [0 1 2 7 8 9]
   (persistent! (sp false)) => [3 4 5 6]"
  {:added "2.1"}
  [ova pchk]
  (let [pos (clone ova)
        neg (clone ova)]
    (filter! pos pchk)
    (remove! neg pchk)
    {true pos false neg}))

(defn !!
  "sets the value of selected data cells in the ova
   
   (-> (range 5)
       (ova)
       (!! 1 0)
       (<<))
   => [0 0 2 3 4]
 
   (-> (range 5)
       (ova)
       (!! #{1 2} 0)
       (<<))
   => [0 0 0 3 4]
 
   (-> (range 5)
       (ova)
       (!! even? 0)
       (<<))
   => [0 1 0 3 0]"
  {:added "2.1"}
  [ova pchk val]
  (smap! ova pchk (constantly val)))

(defmacro <<
  "outputs outputs the entire output of an ova
   
   (-> (ova [1 2 3 4 5])
       (append! 6 7 8 9)
       (<<))
   => [1 2 3 4 5 6 7 8 9]
 
   ;; can also use `persistent!`
   (-> (ova [1 2 3 4 5])
       (persistent!))
   => [1 2 3 4 5]"
  {:added "2.1"}
  [& forms]
  `(let [out# (dosync ~@forms)]
     (persistent! out#)))

(defmacro !>
  "applies a set of transformations to a selector on the ova
   
   (<< (!> (ova [{:id :1}])
           0
           (assoc-in [:a :b] 1)
           (update-in [:a :b] inc)
           (assoc :c 3)))
   => [{:id :1 :c 3 :a {:b 2}}]"
  {:added "2.1"}
  [ova pchk & forms]
  `(smap! ~ova ~pchk
          #(-> % ~@forms)))
