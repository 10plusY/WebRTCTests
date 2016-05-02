(ns test2.core
  (:require [reagent.core :as r]))

(enable-console-print!)

(defn open-stream []
  (let [success (fn [stream]
                  (let [video (. js/document (querySelector "video"))
                        src-url (.. js/window -URL (createObjectURL stream))]
                    (set! (.-src video) src-url)
                    (set! (.-onloadedmetadata video) (fn [e] (.play video)))))
        failure (fn [error]
                  (. js/console (log "Cannot get user media...")))]
    (.. js/navigator (webkitGetUserMedia #js {:video #js {:mandatory #js {:maxWidth 320
                                                                          :maxHeight 180}}}
                                         success
                                         failure))))

;network exchange

;create and configure RTCPeerConnection
;; (def peer-connection
;;   (js/webkitRTCPeerConnection.
;;     #js {:iceServers [{:url "stun:stun.l.google.com:19302"}]}
;;     #js {:optional [{:RTPDataChannels true}]}))

;when ice candidate is found

;when signal channel message received



(defn rtc-view []
  (fn []
    [:div.container
     [:h1 "WebRTC Test"]
     [:video]
     [:button {:on-click (fn [e] (open-stream))}
       "Connect"]]))

(r/render-component [rtc-view]
  (. js/document (getElementById "app")))
