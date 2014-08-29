(ns clairvoyant.core)

(def ^:dynamic *tracer*)
(def ^:dynamic *trace-depth* 0)
(def ^:dynamic *trace-data*)

(def ignored-form?
  '#{def quote try assert})

(defmacro ^:private with-trace-context
  [{:keys [tracer trace-depth trace-data]
    :or {trace-depth 0}}
   & body]
  `(binding [*tracer* ~tracer
             *trace-depth* ~trace-depth
             *trace-data* ~trace-data]
     ~@body))

(defn trace-body
  ([form]
     (trace-body form *trace-data*))
  ([form trace-data]
     `((when (satisfies? ITraceEnter ~*tracer*)
         (trace-enter ~*tracer* ~trace-data))
       (let [return# (if (satisfies? ITraceError ~*tracer*)
                       (try
                         ~form
                         (catch js/Object e#
                           (trace-error ~*tracer* (assoc ~trace-data :error e#))))
                       ~form)]
         (when (satisfies? ITraceExit ~*tracer*)
           (trace-exit ~*tracer* (assoc ~trace-data :exit return#)))
         return#))))

(defmulti trace-form
  (fn [[op & rest]] op)
  :default ::default)

(defmethod trace-form ::default
  [form] form)

;; defn/fn/fn*

(defn normalize-arglist
  "Removes variation from an argument list."
  [arglist]
  (vec (remove '#{&} arglist)))

(defn munge-arglist
  "Given an argument list create a new one with generated symbols."
  [arglist]
  (vec (for [arg arglist]
         (if (= '& arg)
           arg
           (gensym "a_")))))

(defn condition-map? [x]
  (and (map? x)
       (or (vector? (:pre x))
           (vector? (:post x)))))

(defn condition-map-and-body [fn-spec]
  (let [[x & body] fn-spec]
    (if (and (seq body)
             (condition-map? x))
      [x body]
      [nil fn-spec])))

(defn trace-fn-spec
  ([arglist body]
     (trace-fn-spec arglist body *trace-data*))
  ([arglist body trace-data]
     (let [[condition-map body] (condition-map-and-body body)
           munged-arglist (munge-arglist arglist)
           args (normalize-arglist arglist)
           munged-args (normalize-arglist munged-arglist)
           trace-data (assoc trace-data :args `~munged-args)
           form `((fn ~munged-arglist
                    (let ~(vec (interleave args munged-args))
                      ((fn []
                         ~condition-map
                         ~@body))))
                  ~@munged-args)]
       `(~munged-arglist ~@(trace-body form trace-data)))))

(defn trace-fn
  [form]
  (let [[op & body] form
        [sym specs] (if (symbol? (first body))
                      [(first body) (rest body)]
                      [(gensym "fn_") body])
        specs (if (every? list? specs)
                specs
                (list specs))
        trace-data `{:op '~op
                     :form '~form
                     :ns '~(.-name *ns*)
                     :name '~sym
                     :anonymous? true}]
    `(~op ~@(doall (for [[arglist & body] specs
                         :let [trace-data (assoc trace-data :arglist `'~arglist)]]
                     (trace-fn-spec arglist body trace-data))))))

(defmethod trace-form 'fn*
  [form] (trace-fn form))

(defmethod trace-form 'fn
  [form] (trace-fn form))

(defmethod trace-form 'defn
  [form]
  (let [[_ name [_ & fn-body]] (macroexpand-1 form)
        trace-data `{:op 'defn
                     :form '~form
                     :ns '~(.-name *ns*)
                     :name '~name
                     :anonymous? false}
        specs (for [[arglist & body] fn-body]
                (trace-fn-spec arglist body trace-data))]
    `(def ~name
       (fn ~@(doall specs)))))

;; defmulti

;; defmethod

(defmethod trace-form 'defmethod
  [form]
  (let [[op multifn dispatch-val & [arglist & body]] form
        trace-data `{:op '~op
                     :form '~form
                     :ns '~(.-name *ns*)
                     :name '~multifn
                     :dispatch-val '~dispatch-val
                     :arglist '~arglist}]
    `(defmethod ~multifn ~dispatch-val
       ~@(trace-fn-spec arglist body trace-data))))

;; reify

(defn trace-protocol-spec
  [spec-form trace-data]
  (let [[name arglist & body] spec-form
        trace-data (assoc trace-data
                     :name `'~name
                     :form `'~spec-form
                     :arglist `'~arglist)]
    (cons name (trace-fn-spec arglist body trace-data))))

(defmethod trace-form 'reify
  [form]
  (let [[op & body] form
        impls (partition-all 2 (partition-by symbol? body))
        trace-data `{:op '~op
                     :form '~form
                     :ns '~(.-name *ns*)}]
    `(reify
       ~@(mapcat
           (fn [proto+specs]
             (let [proto (ffirst proto+specs)
                   specs (second proto+specs)
                   trace-data (assoc trace-data :protocol `'~proto)
                   specs (for [spec specs]
                           (trace-protocol-spec spec trace-data))]
               `(~proto ~@(doall specs))))
           impls))))

(defmacro trace-forms
  [{:keys [tracer trace-depth]} & forms]
  (if tracer
    (with-trace-context {:tracer tracer :trace-depth trace-depth}
      `(do ~@(doall (map trace-form forms))))
    `(do ~@forms)))