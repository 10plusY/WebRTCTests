(ns server.core
  (:require [org.httpkit.server :refer [run-server]]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [taoensso.sente :as sente]
    [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [org.httpkit.client :as http]
    [clojure.data.json :as json]))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! sente-web-server-adapter {:user-id-fn (fn [_]
                                                                          (gensym "user"))})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

; websocket get-ice responds with ice json from twilio
; use http-kit client to make request to twilio

(defroutes app-routes
 (GET "/" _ "hello")
 (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
 (POST "/chsk" req (ring-ajax-post                req)))

(def app
  (-> app-routes
      ;; Add necessary Ring middleware:
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(defn get-ice-servers []
  (let [response @(http/request
                    {:url "https://api.twilio.com/2010-04-01/Accounts/AC01773bdc00cc61649a19c84303b85c82/Tokens.json"
                     :method :post
                     :basic-auth ["AC01773bdc00cc61649a19c84303b85c82" "702d58243e8fca8feb933c1e9cc56452"]})
        ice-servers (-> response
                        (get :body)
                        (json/read-str :key-fn keyword)
                        (get :ice_servers))]
    ice-servers))

(defn broadcast-ice-candidate [candidate]
  (doseq [user-id (:any @connected-uids)]
    (chsk-send! user-id [:respond/candidate candidate])))

(defn broadcast-session-description [description]
  (doseq [user-id (:any @connected-uids)]
    (chsk-send! user-id [:respond/description description])))

;--WS EVENTS--
(defmulti event-msg-handler :id)

(defn event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (event-msg-handler ev-msg))

(defmethod event-msg-handler :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod event-msg-handler :get/ice-servers
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (?reply-fn (get-ice-servers)))

(defmethod event-msg-handler :post/candidate
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [can-string (get ?data :candidate)]
      (broadcast-ice-candidate ?data)))

(defmethod event-msg-handler :post/description
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [desc-string (get ?data :sdp)]
      (broadcast-session-description ?data)))

(defonce router_ (atom nil))

(defn stop-router! [] (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server []
    (reset! server (run-server #'app {:port 8080})))

(defn -main [&args]
  (start-server))
