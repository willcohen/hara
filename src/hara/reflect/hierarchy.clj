(ns hara.reflect.hierarchy
  (:require [hara.reflect.common :as common]
            [hara.reflect.element.common :as element]
            [hara.class.inheritance :as inheritance]))

(defn has-method
  "Checks to see if any given method exists in a particular class
 
   (has-method without-method
               String)
   => nil
 
   (has-method without-method
               clojure.lang.PersistentArrayMap)
   => clojure.lang.PersistentArrayMap"
  {:added "2.1"}
  [^java.lang.reflect.Method method ^Class class]
  (try (.getDeclaredMethod class
                           (.getName method) (.getParameterTypes method))
       class
       (catch NoSuchMethodException e)))

(defn methods-with-same-name-and-count
  "methods-with-same-name-and-count
 
   (methods-with-same-name-and-count without-method clojure.lang.IPersistentMap)
   =>  #(-> % count (= 1))  ;; (#<Method clojure.lang.IPersistentMap.without(java.lang.Object)>)
 
   "
  {:added "2.1"}
  [^java.lang.reflect.Method method ^Class class]
  (let [methods (.getDeclaredMethods class)
        iname (.getName method)
        iparams (.getParameterTypes method)
        inargs (count iparams)
        smethods (filter (fn [^java.lang.reflect.Method x]
                           (and (= iname (.getName x))
                                (= inargs (count (.getParameterTypes x)))))
                         methods)]
    smethods))

(defn is-assignable?
  ""
  [bcls icls]
  (every? (fn [[^Class b ^Class i]]
            (.isAssignableFrom b i))
          (map list bcls icls)))

(defn has-overridden-method
  "Checks to see that the method can be 
 
   (has-overridden-method without-method String)
   => nil
 
   (has-overridden-method without-method clojure.lang.IPersistentMap)
   => clojure.lang.IPersistentMap"
  {:added "2.1"}
  [^java.lang.reflect.Method method class]
  (let [smethods (methods-with-same-name-and-count method class)
        iparams (.getParameterTypes method)]
    (if (some (fn [^java.lang.reflect.Method smethod]
                (is-assignable?
                 (.getParameterTypes smethod)
                 iparams))
              smethods)
      class)))

(defn origins
  "Lists all the classes tha contain a particular method
 
   (def without-method
     (-> clojure.lang.PersistentArrayMap
         (.getDeclaredMethod \"without\"
                             (into-array [Object]))))
 
   (origins without-method)
   => [clojure.lang.IPersistentMap
       clojure.lang.PersistentArrayMap]"
  {:added "2.1"}
  ([^java.lang.reflect.Method method]
   (origins method (inheritance/ancestor-tree (.getDeclaringClass method))))
  ([^java.lang.reflect.Method method bases]
   (origins method bases (list (.getDeclaringClass method))))
  ([method [[super interfaces :as pair] & more] currents]
     (if (nil? pair) currents
         (let [currents (if-let [current (has-overridden-method method super)]
                          (conj currents current)
                          currents)]
           (if-let [current (first (filter #(has-overridden-method method %) interfaces))]
             (conj currents current)
             (recur method more currents))))))
