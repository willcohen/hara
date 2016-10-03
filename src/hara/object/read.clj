(ns hara.object.read
  (:require [hara.protocol.object :as object]
            [hara.string.case :as case]
            [hara.reflect :as reflect]
            [clojure.walk :as walk]))

(defn meta-read
  ""
  [^Class cls]
  (assoc (object/-meta-read cls) :class cls))

(defn read-reflect-fields
  ""
  [cls]
  (->> (reflect/query-class cls [:field])
       (map (juxt (comp keyword case/spear-case :name)
                  identity))
       (into {})))

(defonce +read-template+
  '(fn <method> [obj] (. obj (<method>))))

(def +read-is-template+
  {:prefix "is" :template +read-template+ :extra "?"})

(def +read-get-template+
  {:prefix "get" :template +read-template+})

(defn read-getters
  ""
  ([cls] (read-getters cls +read-get-template+))
  ([cls {:keys [prefix template extra]}]
   (->> [:method :instance :public (re-pattern (str "^" prefix ".+")) 1]
        (reflect/query-class cls)
        (reduce (fn [out ele]
                  (assoc out
                         (-> (:name ele)
                             (subs (count prefix))
                             case/spear-case
                             (str (or extra ""))
                             keyword)
                         (eval (walk/postwalk-replace {'<method> (symbol (:name ele))}
                                                      template))))
                {}))))

(defn to-data
  ""
  [obj]
  (let [cls (type obj)
        {:keys [to-clojure to-string to-map to-vector methods]} (meta-read cls)]
    (cond (nil? obj) nil

          (instance? java.util.Map obj)
          obj

          to-clojure (to-clojure obj)

          to-string (to-string obj)

          to-map (to-map obj)

          to-vector (to-vector obj)

          methods (reduce-kv (fn [out k func]
                               (if-let [v (func obj)]
                                 (assoc out k (to-data v))
                                 out))
                             {}
                             methods)

          (.isArray ^Class cls)
          (->> (seq obj)
               (mapv to-data))

          (instance? java.lang.Iterable obj)
          (mapv to-data obj)

          (instance? java.util.Iterator obj)
          (->> obj iterator-seq (mapv to-data))

          (instance? java.util.AbstractCollection obj)
          (to-data (.iterator ^java.util.AbstractCollection obj))

          :else obj)))
