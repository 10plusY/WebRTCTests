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

(def peer-connection nil)

(defn open-stream []
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

;network exchange

(defn open-peer-connection [servers]
  (let [conn (js/webkitRTCPeerConnection.
                servers #js {:optional [{:RTPDataChannels true}]})]
    (set! peer-connection conn)))


;when ice candidate is found

;when signal channel message received

(defn rtc-view []
  (fn []
    [:div.container
     [:h1 "WebRTC Test"]
     [:div.in
      [:h2 "In"]
      [:video {:id "in"}]
      [:button {:on-click (fn [e] (open-stream))}
       "Connect"]]
     [:div.out
      [:h2 "Out"]
      [:video {:id "out"}]
      [:button "Receive"]]]))

(r/render-component [rtc-view]
  (. js/document (getElementById "app")))
