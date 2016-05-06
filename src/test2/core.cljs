(ns test2.core
  (:require-macros
    [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [reagent.core :as r]
            [cljs.core.async :as async :refer (<! >! put! chan)]
            [taoensso.sente  :as sente :refer (cb-success?)]))

(enable-console-print!)

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk"
       {:host "localhost:8080"
        :path "/chsk"
        :type :auto })]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state))


;video res constraints i.e. uvga
(defonce constraints
  #js {:video
    #js {:mandatory
       #js {:maxWidth 320
            :maxHeight 180}}})

;RTC peer connection atom
(def peer-connection (atom nil))

;--SOCKET SIGNAL EVENTS--

;decides whether to create description or ice candidate object based on signal
(defn handle-signal [signal]
  (when-not (@peer-connection)
    (start-call))
  (if (.-sdp signal)
    (.setRemoteDescription @peer-connection
                           (js/webkitRTCSessionDescription. (clj->js {:sdp (.-sdp signal)})))
    (.addIceCandidate @peer-connection
                      (js/webkitRTCIceCandidate. (clj->js {:candidate (-.candidate signal)})))))

;signal route for receiving ice candidate broadcasts
(defn signal-ice-candidate [candidate]
  (chsk-send! [:post/candidate {:candidate candidate}]))

;signal route for receiving session description broadcasts
(defn signal-session-description [description]
  (.setLocalDescription @peer-connection description)
  (js/console.log "description" description)
  #_(chsk-send! [:post/description {:type (.-type description)
                                  :sdp (.-sdp description)}]))

;--MEDIA OR CALL EVENTS--

;creates offer or answer depending on if client is caller or callee
(defn create-offer-or-answer []
  (if (.-caller js/window)
    (.createOffer @peer-connection signal-session-description (fn [err] (println err)))
    (.createAnswer @peer-connection signal-session-description (fn [err] (println err)))))

;opens media stream and makes offer or answer
(defn open-local-stream []
  (let [success (fn [stream]
                  (let [video-in (. js/document (getElementById "in"))
                        src-url (.. js/window -URL (createObjectURL stream))]
                    (set! (.-src video-in) src-url)
                    (set! (.-onloadedmetadata video-in) (fn [e] (.play video-in)))
                    (.addStream @peer-connection stream)
                    (create-offer-or-answer)))
        failure (fn [error]
                  (println (.-message error)))]
    (.. js/navigator (webkitGetUserMedia constraints
                                         success
                                         failure))))

;creates RTC peer connection for client and sets handlers
(defn create-rtc-connection [configuration]
  (reset! peer-connection (js/webkitRTCPeerConnection. (clj->js {:iceServers configuration})))
  (set! (.-onicecandidate @peer-connection)
        (fn [evt]
          (js/console.log "candidate" (.-candidate evt))
          #_(signal-ice-candidate (.-candidate evt))))
  (set! (.-onaddstream @peer-connection)
        (fn [evt]
          (println "onaddstream" evt)
          (let [video-out (. js/document (getElementById "out"))]
            (set! (.-src video-out) (.. js/window -url-out (createObjectURL (.-stream evt))))
            (set! (.-onloadedmetadata video-out) (fn [e] (.play video-out))))))
  (open-local-stream))

;requests available ice servers - to be fired when page is opened - i.e. when a new client connections to ws server
(defn request-ice []
  (chsk-send! [:get/ice-servers]
    (fn [servers]
      (create-rtc-connection servers))))

;--SENTE WS EVENTS--

;generic event multimethod
(defmulti event-msg-handler :id)

(defn event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (println (:event ev-msg))
  (event-msg-handler ev-msg))

(defmethod event-msg-handler :default
  [{:as ev-msg :keys [event]}]
  (println "Unhandled event:" event))

(defmethod event-msg-handler :chsk/state
  [_]
  #_(request-ice))

(defmethod event-msg-handler :respond/candidate
  [{:as ev-msg :keys [id ?data event]}]
  (let [candidate ?data]
    (handle-signal candidate)))

(defmethod event-msg-handler :respond/description
  [{:as ev-msg :keys [id ?data event]}]
  (println "received desc" ?data)
  (let [description ?data]
    (handle-signal description)))


(defn start-call []
  (set! (.-caller js/window) true)
  (request-ice))

(defn rtc-view []
  (fn []
    [:div.container
     [:h1 "WebRTC Test"]
     [:div.in
      [:h2 "In"]
      [:video {:id "in"}]
      [:button {:on-click (fn [e] (start-call))}
       "Connect"]]
     [:div.out
      [:h2 "Out"]
      [:video {:id "out"}]
      [:button {:on-click (fn [e]
                          )}
       "Receive"]]]))

(r/render-component [rtc-view]
  (. js/document (getElementById "app")))

(def router_ (atom nil))

(defn stop-router! [] (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(start-router!)
