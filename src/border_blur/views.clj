(ns border-blur.views
  (:require [hiccup.page :refer [html5 include-css include-js]]))

(defn layout [title content]
  "Base HTML layout with Leaflet.js for maps"
  (html5
   [:head
    [:title title]
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    (include-css "/css/style.css")
    ;; Leaflet.js for map integration
    [:link {:rel "stylesheet" :href "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"}]
    [:script {:src "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"}]]
   [:body
    content
    ;; Map JavaScript at bottom of page
    (include-js "/js/map.js")]))

(defn home-page []
  "Landing page for Tel Aviv geography game"
  (layout
   "Border Blur - Tel Aviv Geography Game"
   [:div.container
    [:header
     [:h1 "🌆 Border Blur"]
     [:p.subtitle "Test your Tel Aviv geography knowledge!"]]

    [:main
     [:div.intro
      [:p "This game tests your knowledge of Tel Aviv city boundaries."]
      [:p "You'll see street-view images and decide: Is it in Tel Aviv or not?"]
      [:p "After each answer, we'll reveal the true location on a map!"]]

     [:form.city-form {:method "post" :action "/start-game"}
      [:div.input-group
       [:label {:for "city"} "Have you lived or worked in Tel Aviv?"]
       [:select {:id "city" :name "city" :required true}
        [:option {:value "Yes, I know Tel Aviv well"} "Yes, I know Tel Aviv well"]
        [:option {:value "Somewhat familiar"} "Somewhat familiar with Tel Aviv"]
        [:option {:value "Not familiar"} "Not familiar with Tel Aviv"]]]
      [:button.start-btn {:type "submit"} "Start Tel Aviv Game 🎯"]]]

    [:footer
     [:p "No cookies used - your session is tracked by URL only"]]]))

(defn game-page [game-session current-image]
  "Main game interface with single Tel Aviv image"
  (layout
   (str "Stage " (:current-stage game-session) " of " (:total-stages game-session))
   [:div.game-container
    [:header.game-header
     [:div.progress
      [:span.stage-info "Stage " (:current-stage game-session) " of " (:total-stages game-session)]
      [:span.score "Score: " (:score game-session)]
      (when (> (:streak game-session) 0)
        [:span.streak "🔥 " (:streak game-session)])]
     [:div.progress-bar
      [:div.progress-fill {:style (str "width: "
                                       (* 100 (/ (dec (:current-stage game-session))
                                                 (:total-stages game-session))) "%")}]]]

    [:main.game-main
     [:div.question
      [:h2 "Is it in Tel Aviv?"]]

     [:div.single-image-container
      [:div.image-wrapper
       [:img.game-image {:src (:url current-image)
                         :alt "Street view location"}]
       [:div.difficulty-indicator {:class (name (:difficulty current-image))}
        (case (:difficulty current-image)
          :easy "Easy"
          :medium "Medium"
          :hard "Hard")]]]

     [:form.answer-form {:method "post" :action (str "/game/" (:session-id game-session) "/answer")}
      [:div.answer-buttons
       [:button.answer-btn.yes {:type "submit" :name "answer" :value "yes"}
        "✓ Yes, it's in Tel Aviv"]
       [:button.answer-btn.no {:type "submit" :name "answer" :value "no"}
        "✗ No, it's not in Tel Aviv"]]]]]))

(defn reveal-page [game-session result]
  "Answer reveal page with map showing true location"
  (let [current-image (:current-image result)
        correct? (:correct? result)
        points (:points result)
        is-in-tel-aviv (:is-in-tel-aviv current-image)]
    (layout
     "Answer Revealed!"
     [:div.reveal-container
      [:header.reveal-header
       [:div.result {:class (if correct? "correct" "incorrect")}
        (if correct? "✓ Correct!" "✗ Incorrect")
        [:span.points " +" points " points"]]
       (when (> (:streak result) 0)
         [:div.streak "🔥 Streak: " (:streak result)])
       [:div.score "Total Score: " (:score game-session)]]

      [:main.reveal-main
       [:div.image-result
        [:img.revealed-image {:src (:url current-image)
                              :alt "Location revealed"}]
        [:div.answer-reveal
         [:h3 "The Truth:"]
         [:p.location-answer {:class (if is-in-tel-aviv "tel-aviv" "not-tel-aviv")}
          (if is-in-tel-aviv
            "✓ This is in Tel Aviv"
            "✗ This is NOT in Tel Aviv")]
         [:p.location-name "📍 " (:location-name current-image)]]]

       [:div.map-container
        [:h3 "Location on Map:"]
        [:div#reveal-map.map-display {:data-coords (str (first (:coords current-image)) "," (second (:coords current-image)))
                                      :data-tel-aviv (str is-in-tel-aviv)
                                      :data-location (str (:location-name current-image))}]
        [:div.map-legend
         [:span.tel-aviv-boundary "— Tel Aviv Boundary"]
         [:span.location-marker "📍 Image Location"]]]]

      [:form.next-form {:method "post" :action (str "/game/" (:session-id game-session) "/next")}
       [:button.next-btn {:type "submit"}
        (if (>= (inc (:current-stage game-session)) (:total-stages game-session))
          "View Final Results 🏆"
          "Next Image →")]]])))

(defn results-page [game-session]
  "Final results page"
  (let [total-stages (count (:stages game-session))
        correct-answers (count (filter :correct? (:stages game-session)))
        accuracy (if (> total-stages 0)
                   (int (* 100 (/ correct-answers total-stages)))
                   0)]
    (layout
     "Game Results"
     [:div.results-container
      [:header.results-header
       [:h1 "🎯 Game Complete!"]
       [:div.final-score
        [:span.score-value (:score game-session)]
        [:span.score-label "points"]]
       [:div.accuracy
        [:span.accuracy-value accuracy "%"]
        [:span.accuracy-label "accuracy"]]]

      [:main.results-main
       [:div.game-summary
        [:p "You played " total-stages " stages"]
        [:p "Got " correct-answers " correct answers"]
        (when (> (:streak game-session) 5)
          [:p "🔥 Best streak: " (apply max (map :streak (:stages game-session)))])]

       [:div.stage-breakdown
        [:h3 "Stage by Stage:"]
        [:ul.stages-list
         (for [stage (:stages game-session)]
           [:li.stage-item {:class (if (:correct? stage) "correct" "incorrect")}
            [:span.stage-number "Stage " (:stage stage)]
            [:span.stage-result (if (:correct? stage) "✓" "✗")]
            [:span.stage-points "+" (:points stage) " pts"]])]]

       [:div.play-again
        [:a.play-again-btn {:href "/"} "Play Again 🎮"]]]

      [:footer
       [:p "Thanks for playing Border Blur!"]]])))

(defn error-page [message]
  "Error page"
  (layout
   "Error"
   [:div.error-container
    [:h1 "😕 Oops!"]
    [:p message]
    [:a {:href "/"} "← Back to Home"]]))