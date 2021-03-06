(ns jit-playground.meta-interp
  (:refer-clojure :exclude [resolve compile]))

;; NOTE: this code is highly buggy, and is written mostly for
;; demonstration purposes.

(comment
  ;;  VM Opcodes
  [:assign var value]


  [:binop var op arg1 arg2] ;; op in [+ - =]

  [:nth assign array idx]
  [:set-nth array idx val]

  [:jump block]

  [:if test then else]

  [:promote val block]

  [:guard-true ...]
  [:guard-false ...]
  [:guard-value ...]


  )


(def bf-interp
  '(let [code (promote code)
         data (promote data)
         stack (promote stack)]
     (loop [ip ip
            dp dp
            sp 0]
       (promote ip)
       (promote sp)
       (if (< ip code-length)
         (let [inst (nth code ip)
               ip+one (+ ip 1)]
           (promote  inst)
           (case inst
             \> (recur ip+one (+ dp 1))
             \< (recur ip+one (- dp 1))
             \+ (do (set-nth data dp (+ (nth data dp) 1))
                    (recur ip+one dp))
             \- (do (set-nth data dp (- (nth data dp) 1))
                    (recur ip+one dp))
             \. (do (print-char (nth data dp))
                    (recur ip+one dp))
             \- (do (get-char (nth data dp))
                    (recur ip+one dp))
             \[ (let [sp+one (+ sp 1)]
                  (set-nth stack sp+one ip+one)
                  (recur ip+one dp sp+one))
             \] (let [val (nth data dp)]
                  (if (= val 0)
                    (recur ip+one dp (- sp 1))
                    (let [recur-point (nth stack sp)]
                      (recur-enter-jit [recur-point] recur-point dp sp))))
             (recur ip+one dp sp)))
         (exit)))))


(def ^:dynamic *code*)
(def ^:dynamic *current-block*)
(def ^:dynamic *recur-block*)
(def ^:dynamic *recur-vars*)


(defn new-block
  ([]
      (let [nm (gensym "block_")]
        (swap! *code* assoc nm [])
        nm))
  ([nm data]
     (swap! *code* assoc nm data)
     nm))

(defn add-inst [& args]
  (swap! *code* update-in [@*current-block*] conj (vec args)))

(defn add-binop-inst [op a b]
  (let [nm (gensym op)]
    (add-inst :binop nm op a b)
    nm))

(defn get-block []
  @*current-block*)

(defn set-block [block]
  (reset! *current-block* block))


(def analyze 0)
(defmulti analyze (fn [x]
                    (cond
                     (seq? x) :seq
                     (symbol? x) :symbol
                     (number? x) :number
                     (char? x) :char
                     :else nil)))

(defmulti analyze-seq first)

(defmethod analyze :seq
  [seq]
  (analyze-seq seq))

(defmethod analyze :symbol
  [s]
  s)

(defmethod analyze :char
  [s]
  s)

(defmethod analyze :number
  [n] n)

(defmethod analyze nil
  [n]
  (let [v (gensym "nil")]
    (add-inst :assign v 0)
    v))


(defmethod analyze-seq 'let
  [[_ bindings & body]]
  (doseq [[nm expr] (partition 2 bindings)]
    (let [val (analyze expr)]
      (add-inst :assign nm val)))
  (doseq [expr (butlast body)]
    (analyze expr))
  (analyze (last body)))

(defmethod analyze-seq 'do
  [[_ & body]]
  (doseq [expr (butlast body)]
    (analyze expr))
  (analyze (last body)))

(defmethod analyze-seq 'promote
  [[_ val]]
  (let [block (new-block)
        val (analyze val)]
    (add-inst :promote val block)
    (set-block block)
    val))

(defmethod analyze-seq 'loop
  [[_ bindings & body]]
  (let [parted (partition 2 bindings)
        body-block (new-block)]
    (doseq [[nm expr] parted]
      (let [val (analyze expr)]
        (add-inst :assign nm val)))
    (add-inst :jump body-block)
    (set-block body-block)
    (binding [*recur-block* body-block
              *recur-vars* (mapv first parted)]
      (doseq [expr body]
        (analyze expr)))
    nil))

(defmethod analyze-seq '<
  [[_ a b]]
  (add-binop-inst '< (analyze a) (analyze b)))

(defmethod analyze-seq '+
  [[_ a b]]
  (add-binop-inst '+ (analyze a) (analyze b)))

(defmethod analyze-seq '-
  [[_ a b]]
  (add-binop-inst '- (analyze a) (analyze b)))

(defmethod analyze-seq '=
  [[_ a b]]
  (add-binop-inst '= (analyze a) (analyze b)))

(defmethod analyze-seq 'nth
  [[_ a b]]
  (let [nm (gensym "nth")]
    (add-inst :nth nm (analyze a) (analyze b))
    nm))

(defmethod analyze-seq 'print-char
  [[_ a]]
  (let [nm (gensym "print-char")]
    (add-inst :print-char (analyze a))
    nm))

(defmethod analyze-seq 'get-char
  [[_]]
  (let [nm (gensym "get-char")]
    (add-inst :get-char)
    nm))

(defmethod analyze-seq 'exit
  [[_]]
  (add-inst :exit))

(defmethod analyze-seq 'set-nth
  [[_ a b c]]
  (let [nm (gensym "set-nth")]
    (add-inst :set-nth (analyze a) (analyze b) (analyze c))
    nm))

(defmethod analyze-seq 'if
  [[_ test then else]]
  (let [then-block (new-block)
        else-block (new-block)]
    (add-inst :if (analyze test)
              then-block
              else-block)
    (set-block then-block)
    (analyze then)
    (set-block else-block)
    (analyze else)
    :terminated))

(defmethod analyze-seq 'recur
  [[_ & args]]
  (let [args (mapv analyze args)]
    (doall (map (fn [a b]
                  (add-inst :assign a b))
                *recur-vars*
                args))
    (add-inst :jump *recur-block*)
    :terminated))

(defmethod analyze-seq 'recur-enter-jit
  [[_ greens & args]]
  (assert (vector? greens))
  (let [args (mapv analyze args)]
    (doall (map (fn [a b]
                  (add-inst :assign a b))
                *recur-vars*
                args))
    (add-inst :jump-enter-jit greens *recur-block*)
    :terminated))

(defmethod analyze-seq 'case
  [[_ var & cases]]
  (assert (odd? (count cases)) "need a default expr")
  (let [var (analyze var)
        default (last cases)
        parted (partition 2 (butlast cases))
        transformed (reduce
                     (fn [acc [cvar expr]]
                       `(if (~'= ~var ~cvar)
                          ~expr
                          ~acc))
                     default
                     (reverse parted))]
    (analyze transformed)
    :terminated))

(defn const? [x]
  (not (symbol? x)))

(defn resolve [env arg]
  (if (const? arg)
    arg
    (if (contains? env arg)
      (get env arg)
      (assert false (str arg " not found in " env)))))

(defn do-op [op a b]
  (condp = op
    '+ (+ a b)
    '< (< a b)
    '= (= a b)
    '- (- a b)))

(def ^:dynamic *jit-counts*)
(def ^:dynamic *jit-threshold*)
(def ^:dynamic *jit-keys*)


(defn interp [block ip env]
  (let [inst (if (< ip (count block))
               (nth block ip)
               (assert false (pr-str block)))
        ip+1 (inc ip)]
    (case (first inst)
      :promote (let [[_ arg block] inst]
                 (recur (get @*code* block) 0 env))

      :assign (let [[_ to from] inst]
                (recur block
                       ip+1
                       (assoc env to (resolve env from))))

      :jump (let [[_ block] inst]
              (recur (get @*code* block) 0 env))

      :jump-enter-jit (let [[_ greens block] inst
                            rgreens (mapv (partial resolve env) greens)
                            jit-key [block rgreens]]
                        (if-let [block (get @*jit-keys* jit-key)]
                          (do (println "found trace")
                              (clojure.pprint/pprint (get @*code* block))
                            (recur (get @*code* block) 0 env))
                          (do (swap! *jit-counts* update-in [jit-key] (fnil inc 0))
                              (if (> *jit-threshold* (get @*jit-counts* jit-key 0))
                                {:mode :trace
                                 :jit-key jit-key
                                 :block block
                                 :env env
                                 :greens rgreens}
                                (recur (get @*code* block) 0 env)))))

      :binop (let [[_ to op a b] inst
                   ra (resolve env a)
                   rb (resolve env b)]
               (recur block
                      ip+1
                      (assoc env to (do-op op ra rb))))

      :if (let [[_ test then else] inst
                rtest (resolve env test)]
            (if rtest
              (recur (get @*code* then) 0 env)
              (recur (get @*code* else) 0 env)))

      :nth (let [[_ to v idx] inst
                 rv (resolve env v)
                 ridx (resolve env idx)]
             (assert (and (< ridx (count rv))
                          (<= 0 ridx)) (pr-str (type rv) ridx))
             (recur block
                    ip+1
                    (assoc env to (nth rv ridx))))

      :print-char (let [[_ var] inst
                        rvar (resolve env var)]
                    (print (char rvar))
                    (recur block ip+1 env))

      :set-nth (let [[_ arr idx val] inst
                     rarr (resolve env arr)
                     ridx (resolve env idx)
                     rval (resolve env val)]
                 (aset rarr ridx rval)
                 (recur block
                        ip+1
                        env))

      :guard-value (let [[_ v c blk] inst
                         rv (resolve env v)]
                     (if (= rv c)
                       (recur block ip+1 env)
                       (recur (get @*code* blk) 0 env)))

      :guard-true (let [[_ v blk] inst
                         rv (resolve env v)]
                     (if rv
                       (recur block ip+1 env)
                       (recur (get @*code* blk) 0 env)))

      :guard-false (let [[_ v blk] inst
                         rv (resolve env v)]
                     (if (not rv)
                       (recur block ip+1 env)
                       (recur (get @*code* blk) 0 env)))

      :loop (recur block 0 env)

      :exit {:mode :exit
             :env env
             :block block})))


(def hello-world "++++++++++[>+++++++>++++++++++>+++>+<<<<-]>++.>+.+++++++..+++.>++.<<+++++++++++++++.>.+++.------.--------.>+.>.")


(def ^:dynamic *trace-end-block*)

(defn trace
  ([block ip env]
     (trace block ip env []))
  ([block ip env trace]
      (let [inst (nth block ip)
            ip+1 (inc ip)]
        (case (first inst)
          :promote (let [[_ arg block] inst
                         rarg (resolve env arg)]
                     (recur (get @*code* block) 0 env
                            (conj trace [:guard-value arg rarg block])))

          :assign (let [[_ to from] inst]
                    (recur block
                           ip+1
                           (assoc env to (resolve env from))
                           (conj trace inst)))


          :jump (let [[_ block] inst]
                  (recur (get @*code* block) 0 env trace))

          :jump-enter-jit (let [[_ greens block] inst
                                rgreens (mapv (partial resolve env) greens)]

                            {:mode :optimize
                             :block block
                             :env env
                             :trace (conj trace [:loop])})

          :binop (let [[_ to op a b] inst
                       ra (resolve env a)
                       rb (resolve env b)]
                   (recur block
                          ip+1
                          (assoc env to (do-op op ra rb))
                          (conj trace inst)))

          :if (let [[_ test then else] inst
                    rtest (resolve env test)]
                (if rtest
                  (recur (get @*code* then) 0 env (conj trace [:guard-true test else]))
                  (recur (get @*code* else) 0 env (conj trace [:guard-false test then]))))

          :nth (let [[_ to v idx] inst
                     rv (resolve env v)
                     ridx (resolve env idx)]
                 (recur block
                        ip+1
                        (assoc env to (nth rv ridx))
                        (conj trace inst)))

          :print-char (let [[_ var] inst
                            rvar (resolve env var)]
                        (print (char rvar))
                        (recur block ip+1 env (conj trace inst)))

          :set-nth (let [[_ arr idx val] inst
                         rarr (resolve env arr)
                         ridx (resolve env idx)
                         rval (resolve env val)]
                     (aset rarr ridx rval)
                     (recur block
                            ip+1
                            env
                            (conj trace inst)))

          :exit {:mode :exit
                 :env env
                 :block block
                 :trace trace}))))


(declare generate-assignments)

(defn presolve [env x]
  (assert (map? env))
  (if (symbol? x)
    (if (contains? env x)
      (get env x)
      x)
    x))

(def ^:dynamic *orig-env*)

(defn gen-guard-block [penv block]
  (let [block-insts (map (fn [[k v]]
                      [:assign k v])
                          penv)
        nm (gensym "fixup_")]
    (new-block nm (vec (concat block-insts
                               [[:jump block]])))))

(defn optimize
  ([trace penv]
     (let [result (optimize trace penv [])
           nm (gensym "trace_")]
       (println "Optimized trace: " (count trace) " ->" (count result))
       #_(clojure.pprint/pprint result)
       (new-block nm result)
       nm))
  ([trace penv new-block]
      (let [inst (first trace)]
        (case (first inst)
          :assign (let [[_ to from] inst
                        rfrom (presolve penv from)]
                    (cond (const? rfrom)
                      (recur (next trace)
                             (assoc penv to rfrom)
                             new-block)

                      (= to from) ; no op
                      (recur (next trace)
                             penv
                             new-block)

                      :else
                      (recur (next trace)
                             (dissoc penv to)
                             (conj new-block
                                   inst))))

          :binop (let [[_ to op a b] inst
                       ra (presolve penv a)
                       rb (presolve penv b)]
                   (if (and (const? ra)
                            (const? rb))
                     (recur (next trace)
                            (assoc penv to (do-op op ra rb))
                            new-block)
                     (recur (next trace)
                            (dissoc penv to)
                            (conj new-block inst))))

          :print-char (let [[_ var] inst]
                        (recur (next trace)
                               penv
                               (conj new-block inst)))


          :guard-true (let [[_ v block] inst
                            val (presolve penv v)]
                        (if (const? val)
                          (recur (next trace)
                                 penv
                                 new-block)
                          (recur (next trace)
                                 penv
                                 (conj new-block
                                       [:guard-true v (gen-guard-block penv block)]))))


          :guard-false  (let [[_ v block] inst
                              val (presolve penv v)]
                          (if (const? val)
                            (recur (next trace)
                                   penv
                                   new-block)
                            (recur (next trace)
                                   (assoc penv v false)
                                   (conj
                                    new-block
                                    [:guard-false v (gen-guard-block penv block)]))))

          :guard-value (let [[_ v c block] inst
                             val (presolve penv v)]
                         (if (and (const? val)
                                  (= val c))
                           (recur (next trace)
                                  (assoc penv v val)
                                  new-block)
                           (recur (next trace)
                                  (assoc penv v c)
                                  (conj
                                   new-block
                                   [:guard-value v c (gen-guard-block penv block)]))))

          :nth (let [[_ to from idx] inst
                     rfrom (presolve penv from)
                     ridx (presolve penv idx)]
                 (if (and (string? rfrom) ;; For now only fully constant fold strings
                          (const? ridx))
                   (recur (next trace)
                          (assoc penv to (nth rfrom ridx))
                          new-block)
                   (recur (next trace)
                          (dissoc penv to)
                          (conj new-block inst))))

          :set-nth (let [[_ to from idx] inst]
                     (recur (next trace)
                            (dissoc penv to)
                            (conj new-block inst)))

          :loop (generate-assignments new-block penv)))))


(defn generate-assignments [new-block penv]
  (concat new-block
          (for [[k v] penv]
            [:assign k v])
          [[:loop]]))

(defn new-bf-env [code]
  {'code code
   'code-length (count code)
   'data (object-array (repeat 10024 0))
   'stack (object-array 32)
   'ip 0
   'dp 0
   'sp 0
   })


(defn run-program [program]
  (let [start-status {:mode :interp
                      :block :entry
                      :env (new-bf-env program)}]
    (loop [status start-status]
      (println (:mode status) (dissoc status :env :trace))
      (let [block (get @*code* (:block status))
            env (:env status)]
        (case (:mode status)
          :interp (recur (interp block 0 env))

          :trace (recur (assoc (trace block 0 env)
                          :jit-key (:jit-key status)))

          :optimize (let [bk (optimize (:trace status) {'code program
                                                        'code-length (count program)})]
                      (assert (:jit-key status))
                      (swap! *jit-keys* assoc (:jit-key status) bk)
                      (recur {:mode :interp
                              :block bk
                              :env env}))
          :exit (do (println "Finished")
                    env))))))

(binding [*code* (atom {:entry []})
          *current-block* (atom :entry)
          *jit-keys* (atom {})
          *jit-counts* (atom {})
          *jit-threshold* 2]
  (analyze bf-interp)
  (run-program hello-world)
  (println @*jit-keys*))
