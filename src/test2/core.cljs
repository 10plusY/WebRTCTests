(ns test2.core
  (:require [reagent.core :as r]))

(enable-console-print!)

(defonce constraints
  #js {:video
    #js {:mandatory
       #js {:maxWidth 320
            :maxHeight 180}}})

(defonce google-stun-server
  #js {:iceServers [{:url "stun:stun.l.google.com:19302"}]})

(def local-peer-connection nil)
(def remote-peer-connection nil)

;Media functions/events
(defn open-local-stream []
  (let [success (fn [stream]
                  (let [video (. js/document (getElementById "in"))
                        src-url (.. js/window -URL (createObjectURL stream))]
                    (set! (.-src video) src-url)
                    (set! (.-onloadedmetadata video) (fn [e] (.play video)))))
        failure (fn [error]
                  (println "Cannot get user media...")
                  (println (.message error)))]
    (.. js/navigator (webkitGetUserMedia constraints
                                         success
                                         failure))))

;RTC functions/events

;TODO: method for generic connection
;connect for local peer
(defn open-local-peer-connection [servers]
  (set! local-peer-connection
    (js/webkitRTCPeerConnection.
      servers #js {:optional [{:RTPDataChannels true}]})))

;connect for remote peer
(defn open-remote-peer-connection [servers]
  (set! remote-peer-connection
    (js/webkitRTCPeerConnection.
      servers #js {:optional [{:RTPDataChannels true}]})))


;when ice candidate is found

;when signal channel message received

(defn rtc-view []
  (fn []
    [:div.container
     [:h1 "WebRTC Test"]
     [:div.in
      [:h2 "In"]
      [:video {:id "in"}]
      [:button {:on-click (fn [e] (open-local-stream))}
       "Connect"]]
     [:div.out
      [:h2 "Out"]
      [:video {:id "out"}]
      [:button "Receive"]]]))

(r/render-component [rtc-view]
  (. js/document (getElementById "app")))