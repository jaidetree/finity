(ns scripts.release
  (:require
   [clojure.string :as s]
   ["child_process" :as cp]
   ["fs" :as fs]))

(def repo-url "https://github.com/jaidetree/finity")

(def files ["build.edn" "package.json" "package-lock.json" "README.md"])
(def snapshot-files #{"build.edn" "README.md"})

(defn- exit
  [code]
  (js/process.exit code))

(defn help
  []
  (println "\nUSAGE:
  nbb -m scripts.release version YYYY.M.D
  nbb -m scripts.release prepare YYYY.M.D")
  (exit 1))

(def date-pattern #"[\d]{4}\.(?!0)[\d]{1,2}\.(?!0)[\d]{1,2}")
(def version-pattern #"[\d]{4}\.[\d]{1,2}\.[\d]{1,2}(?:-SNAPSHOT)?")

(comment
  (re-find date-pattern "2025.03.05")
  (re-find date-pattern "2025.3.5")
  (re-find version-pattern "2025.03.05-SNAPSHOT"))

(defn assert-date
  [input]
  (assert (not (nil? (re-find date-pattern input)))
          (str "Expected date input in YYYY.M.D format, got " input)))

(defn- slurp
  [filepath]
  (.readFileSync fs filepath #js {:encoding "utf-8"}))

(defn- spit
  [filepath contents]
  (.writeFileSync fs filepath contents #js {:encoding "utf-8"}))

(defn update-version
  "Updates version across files given valid date string with no leading zeros

  Arguments:
  - date - YYYY.M.D[-SNAPSHOT] string

  Returns nil"
  [date & _args]
  (let [[date suffix] (s/split (js/String date) #"-")]
    (assert-date date)
    (doseq [file files]
      (let [contents (slurp file)
            version (if (contains? snapshot-files file)
                      (str date "-" (s/upper-case (or suffix "")))
                      date)]
        (spit file (s/replace contents version-pattern version))))
    (println "Update version to" (if suffix (str date "-" suffix) date))))

(defn flatten-args
  [args]
  (->> args
       (mapcat (fn [arg]
                 (cond
                   (map? arg) (list arg)
                   (s/starts-with? arg "\"") (list arg)
                   :else (s/split arg #" "))))))

(comment
  (flatten-args ["git commit -m" "\"my message\"" "branch --HEAD"]))

(defn parse-args
  [args]
  (let [[cmd & args] (flatten-args args)
        arg-or-opts (last args)]
    (if (map? arg-or-opts)
      [cmd (flatten-args (butlast args)) arg-or-opts]
      [cmd (flatten-args args) {}])))

(comment
  (parse-args ["git commit -m" "\"my message\"" "branch --HEAD" {:stdio ["inherit" "inherit" "inherit"]}]))

(defn $
  [& args]
  (let [[cmd args opts] (parse-args args)
        result (.spawnSync cp
                           cmd
                           (clj->js args)
                           (-> (merge
                                {:encoding "utf-8"
                                 :shell true
                                 :stdio ["pipe" "pipe" "pipe"]}
                                opts)
                               (clj->js)))]

    (println cmd (s/join " " args))

    {:status (.-status result)
     :signal (.-signal result)
     :output (js->clj (.-output result))
     :stdout (.-stdout result)
     :stderr (.-stderr result)}))

(defn lines
  [s]
  (->> (s/split s #"\n")
       (filter #(not (s/blank? %)))))

(defn uncommitted-files
  []
  (let [{:keys [stdout] :as result} ($ "git diff-index --name-status HEAD")
        files (->> (s/trim stdout)
                   (lines))]
    (println (prn-str files))
    (if (zero? (count files))
      nil
      files)))

(defn read
  [prompt & [default]]
  (let [result ($ "read -p" (str "\"" prompt ": \"")
                  {:stdio ["inherit" "pipe" "pipe"]})]
    (get result :output default)))

(defn prepare-release
  [date & _args]
  (try
    (assert-date date)
    (let [[date _suffix] (s/split date #"-")
          release-url (str repo-url "/releases/new?tag=" date "&title=" date)]
      (println "\nCheckout main branch")
      ($ "git checkout main")

      (when-let [files (uncommitted-files)]
        (println "Error: Working directory is not clean")
        (println (->> files
                      (s/join "\n")))
        (exit 1))

      (println "\nUpdating version to" date)
      (update-version date)

      (when-not (uncommitted-files)
        (println "Error: No files were modified")
        (exit 1))

      ($ "git add" (s/join " " files))
      ($ "git commit -m" (str "\"chore: Update version to " date "\""))

      (println "\nCreating git tag")
      ($ "git tag -a" (str "\"" date "\"") "-m" (str "\"Prepare release" date "\""))

      (println "\nPushing git tag and main branch")
      ($ "git push origin main" date "--force-with-lease")

      (println "\n" release-url)

      (println "Open release page in browser?")
      (let [input (read "(Y/n) [n]" "n")]
        (when (s/starts-with? (s/lower-case input) "y")
          ($ "open" release-url {:stdio ["inherit" "inherit" "inherit"]}))))

    (catch :default error
      (println (.-message error))
      (exit 1))))

(defn -main
  "Updates the version string across files. Only includes -SNAPSHOT suffix in
  build.edn and README.md

  Arguments:
  - help - Show usage
  - YYYY.M.D[-SNAPSHOT] - Updates the version across files. No leading zeros.

  Usage:
  nbb -m scripts.version 2025.04.16-SNAPSHOT"
  [subcmd & args]
  (try
    (case (s/lower-case subcmd)
      "help" (help)

      "version" (apply update-version args)

      "prepare" (apply prepare-release args)

      (help))
    (catch :default error
      (println (.-message error))
      (help))))
