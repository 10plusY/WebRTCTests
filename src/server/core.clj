(ns server.core
  (:require [org.httpkit.server :refer [run-server]]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [taoensso.sente :as sente]
    [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! sente-web-server-adapter {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defroutes app-routes
 (GET "/" req "hello")
 (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
 (POST "/chsk" req (ring-ajax-post                req)))


(def app
  (-> app-routes
      ;; Add necessary Ring middleware:
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))


(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server []
    (reset! server (run-server #'app {:port 8080})))

(defn -main [&args]
  (start-server))
