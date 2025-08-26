(ns border-blur.views
  (:require [hiccup.page :refer [html5 include-css include-js]]))

(defn layout [title content]
  "Base HTML layout"
  (html5
   [:head
    [:title title]
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    (include-css "/css/style.css")]
   [:body content]))

(defn home-page []
  "Landing page for city input"
  (layout
   "Border Blur - City Recognition Game"
   [:div.container
    [:header
     [:h1 "🌆 Border Blur"]
     [:p.subtitle "Can you tell which city a photo was taken in?"]]

    [:main
     [:div.intro
      [:p "This game tests your knowledge of city boundaries and neighborhoods."]
      [:p "You'll see pairs of images taken near city borders - can you tell if they're from the same city or different cities?"]]

     [:form.city-form {:method "post" :action "/start-game"}
      [:div.input-group
       [:label {:for "city"} "First, tell us a city you know really well:"]
       [:input {:type "text"
                :id "city"
                :name "city"
                :placeholder "e.g., Tel Aviv, London, New York..."
                :required true
                :autocomplete "address-level2"}]]
      [:button.start-btn {:type "submit"} "Start Game 🎯"]]]

    [:footer
     [:p "No cookies used - your session is tracked by URL only"]]]))

(defn game-page [game-session image-pair]
  "Main game interface with two images"
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
      [:h2 "Are these images from the same city?"]]

     [:div.image-pair
      [:div.image-container
       [:img.game-image {:src (get-in image-pair [:left :url])
                         :alt "Location A"}]
       [:p.image-label "Image A"]]

      [:div.vs [:span "VS"]]

      [:div.image-container
       [:img.game-image {:src (get-in image-pair [:right :url])
                         :alt "Location B"}]
       [:p.image-label "Image B"]]]

     [:form.answer-form {:method "post" :action (str "/game/" (:session-id game-session) "/answer")}
      [:div.answer-buttons
       [:button.answer-btn.same {:type "submit" :name "answer" :value "same"}
        "✓ Same City"]
       [:button.answer-btn.different {:type "submit" :name "answer" :value "different"}
        "✗ Different Cities"]]]]]))

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