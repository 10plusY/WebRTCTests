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

(def is-caller (atom false))

(def local-ice-candidate (atom nil))

(defn who-is-calling []
  (if @is-caller
    "caller"
    "callee"))

;--SOCKET SIGNAL EVENTS--

;signal route for receiving ice candidate broadcasts
(defn signal-ice-candidate [candidate]
  (when candidate
    (chsk-send! [:post/candidate {:candidate (.-candidate candidate)
                                  :sdpMid (.-sdpMid candidate)
                                  :sdpMLineIndex (.-sdpMLineIndex candidate)}])))

;signal route for receiving session description broadcasts
(defn signal-session-description [description]
  (.setLocalDescription @peer-connection description)

  ;logic to figure out whether to send description again
  (if (= "offer" (.-type description)) (println "GOT OFFER") (println "GOT ANSWER"))
  (when @is-caller
    (chsk-send! [:post/description {:sdp (.-sdp description)
                                    :type (.-type description)}]))
  (when (= "answer" (.-type description))
    (chsk-send! [:post/description {:sdp (.-sdp description)
                                    :type (.-type description)}])))

;--MEDIA OR CALL EVENTS--

;creates offer or answer depending on if client is caller or callee
(defn create-offer []
  (.createOffer @peer-connection signal-session-description (fn [err] (println err))))

(defn create-answer []
  (.createAnswer @peer-connection signal-session-description (fn [err] (println err))))

;opens media stream and makes offer or answer
(defn open-local-stream []
  (let [success (fn [stream]
                  (let [video-in (. js/document (getElementById "in"))
                        src-url (.. js/window -URL (createObjectURL stream))]
                    (set! (.-src video-in) src-url)
                    (set! (.-onloadedmetadata video-in) (fn [e] (.play video-in)))
                    (.addStream @peer-connection stream)
                    (create-offer)))
        failure (fn [error]
                  (println (.-message error)))]
    (.. js/navigator (webkitGetUserMedia constraints
                                         success
                                         failure))))

;--CALL ENVIRONMENT EVENTS--

;creates RTC peer connection for client and sets handlers
(defn create-rtc-connection [configuration]
  (reset! peer-connection (js/webkitRTCPeerConnection. (clj->js {:iceServers configuration})))
  (set! (.-onicecandidate @peer-connection)
        (fn [evt]
          (let [candidate (.-candidate evt)]
            (when @is-caller
              (when-not @local-ice-candidate
                (reset! local-ice-candidate candidate)
                (js/console.log "ice candidate to be used" @local-ice-candidate)
                (signal-ice-candidate candidate))))))
  (set! (.-onaddstream @peer-connection)
        (fn [evt]
          (println "onaddstream" evt)
          #_(let [video-out (. js/document (getElementById "out"))]
            (set! (.-src video-out) (.. js/window -url-out (createObjectURL (.-stream evt))))
            (set! (.-onloadedmetadata video-out) (fn [e] (.play video-out))))))
  (js/console.log "peer init" @peer-connection))

;requests available ice servers and passes them on to peer connection constructor
(defn request-ice []
  (chsk-send! [:get/ice-servers] 1000
    (fn [servers]
      (create-rtc-connection servers))))

;--SENTE WS EVENTS--

;generic event multimethod
(defmulti event-msg-handler :id)

(defmulti event-handler (fn [[id _]] id))

(defn event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (event-msg-handler ev-msg))

(defmethod event-msg-handler :default
  [{:as ev-msg :keys [event]}]
  (println "Unhandled event:" event))

(defmethod event-msg-handler :chsk/state
  [_]
  #_(request-ice))

(defmethod event-msg-handler :chsk/recv
  [{:as ev-msg :keys [event ?data]}]
  (event-handler ?data))

(defmethod event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [id ?data event]}]
  (println "user" event "logged in")
  (js/console.log "local canidate" @local-ice-candidate)
  (request-ice))

;response event multimethod

;decides whether to create description or ice candidate object based on signal
(defn handle-signal [signal]
  (if (get signal :sdp)
    (let [desc-signal (js/RTCSessionDescription. (clj->js {:sdp (get signal :sdp)
                                                           :type (get signal :type)}))]
      (.setRemoteDescription @peer-connection desc-signal)
      (when-not @is-caller
        (create-answer)))
    (let [ice-signal (js/RTCIceCandidate. (clj->js {:candidate (get signal :candidate)
                                                    :sdpMid (get signal :sdpMid)
                                                    :sdpMLineIndex (get signal :sdpMLineIndex)}))]
      (js/console.log "created ice cand" ice-signal)
      (.addIceCandidate @peer-connection ice-signal))))

(defmethod event-handler :respond/candidate
  [[_ ?data]]
  (let [candidate ?data]
    (println "rec candidate" ?data)
    (handle-signal candidate)))

(defmethod event-handler :respond/description
  [[_ ?data]]
  (let [description ?data]
    (handle-signal description)))

(defn start-call []
  (reset! is-caller true)
  ;TODO - request ICE when page loads
  (open-local-stream))

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
      [:button {:on-click (fn [e] )}
       "Receive"]]]))

(r/render-component [rtc-view]
  (. js/document (getElementById "app")))

(def router_ (atom nil))

(defn stop-router! [] (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(start-router!)
