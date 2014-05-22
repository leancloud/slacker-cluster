(ns slacker.server.cluster
  (:require [slacker.zk :as zk])
  (:use [slacker common serialization])
  (:use [clojure.string :only [split]])
  (:require [slacker.server])
  (:require [slacker.utils :as utils])
  (:require [clojure.tools.logging :as logging])
  (:import java.net.Socket))

(declare ^{:dynamic true} *zk-conn*)

(defn- auto-detect-ip
  "detect IP by connecting to zookeeper"
  [zk-addr]
  (let [zk-address (split zk-addr #":")
        zk-ip (first zk-address)
        zk-port (Integer/parseInt (second zk-address))
        socket (Socket. ^String ^Integer zk-ip zk-port)
        local-ip (.getHostAddress (.getLocalAddress socket))]
    (.close socket)
    local-ip))

(defn- create-node
  "get zk connector & node  :persistent?
   check whether exist already
   if not ,create & set node data with func metadata
   "
  [zk-conn node-name
   & {:keys [data persistent?]
      :or {persistent? false}}]
  (when-not (zk/exists zk-conn node-name)
    (zk/create-all zk-conn node-name
                   :persistent? persistent?
                   :data data)))

(defn publish-cluster
  "publish server information to zookeeper as cluster for client"
  [cluster port ns-names funcs-map]
  (let [cluster-name (cluster :name)
        zk-root (cluster :zk-root "/slacker/cluster/")
        server-node (str (or (cluster :node)
                             (auto-detect-ip (first (split (:zk cluster) #","))))
                         ":" port)
        funcs (keys funcs-map)

        leader-mutex-path (utils/zk-path zk-root cluster-name "leader" "mutex")

        ephemeral-servers-node-paths (conj (map #(utils/zk-path zk-root
                                                                cluster-name
                                                                "namespaces"
                                                                %
                                                                server-node)
                                                ns-names)
                                           (utils/zk-path zk-root
                                                          cluster-name
                                                          "servers"
                                                          server-node))]

    ;; persistent nodes
    (create-node *zk-conn* (utils/zk-path zk-root cluster-name "servers")
                 :persistent? true)

    (doseq [nn ns-names]
      (create-node *zk-conn* (utils/zk-path zk-root
                                            cluster-name
                                            "namespaces"
                                            nn)
                   :persistent? true))

    (doseq [fname funcs]
      (create-node *zk-conn*
                   (utils/zk-path zk-root cluster-name "functions" fname)
                   :persistent? true
                   :data (serialize
                          :clj
                          (select-keys
                           (meta (funcs-map fname))
                           [:name :doc :arglists])
                          :bytes)))

    ;; create leader election paths
    (create-node *zk-conn* leader-mutex-path :persistent? true)

    (let [ephemeral-nodes (doall (map #(zk/create-persistent-ephemeral-node *zk-conn* %)
                                      ephemeral-servers-node-paths))
          p (promise)
          leader-selector (zk/start-leader-election *zk-conn*
                                                    leader-mutex-path
                                                    (fn [conn]
                                                      (logging/infof "% is becoming the leader" server-node)
                                                      (zk/set-data conn
                                                                   (utils/zk-path zk-root cluster-name "leader")
                                                                   (.getBytes ^String server-node "UTF-8"))
                                                      ;; block forever
                                                      @p))]
      [ephemeral-nodes p leader-selector])))

(defmacro with-zk
  "publish server information to specifized zookeeper for client"
  [zk-conn & body]
  `(binding [*zk-conn* ~zk-conn]
     ~@body))

(defn start-slacker-server
  "Start a slacker server to expose all public functions under
  a namespace. This function is enhanced for cluster support. You can
  supply a zookeeper instance and a cluster name to the :cluster option
  to register this server as a node of the cluster."
  [exposed-ns port & options]
  (let [svr (apply slacker.server/start-slacker-server
                   exposed-ns
                   port
                   options)
        {:keys [cluster]} options
        exposed-ns (if (coll? exposed-ns) exposed-ns [exposed-ns])
        funcs (apply merge
                     (map slacker.server/ns-funcs exposed-ns))
        zk-conn (zk/connect (:zk cluster))
        zk-recipes (when-not (nil? cluster)
                   (with-zk zk-conn
                     (publish-cluster cluster port
                                      (map ns-name exposed-ns) funcs)))]
    [svr zk-conn zk-recipes]))

(defn stop-slacker-server [server-tuple]
  (let [[svr zk-conn zk-recipes] server-tuple
        [zk-ephemeral-nodes release-leader leader-selector] zk-recipes]
    (doseq [n zk-ephemeral-nodes]
      (zk/uncreate-persistent-ephemeral-node n))
    (deliver release-leader nil)
    (zk/stop-leader-election leader-selector)
    (zk/close zk-conn)
    (slacker.server/stop-slacker-server svr)))
