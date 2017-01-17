(ns webapp.conversations
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require
    [reagent.core :as reagent :refer [atom create-class]]
    [reagent.ratom :refer-macros [reaction]]
    [webapp.state :as state]
    [clojure.string :as string]
    [taoensso.sente  :as sente  :refer (cb-success?)]
    [cljs-http.client :as http]
    [cljs-time.core    :as time]
    [cljs-time.coerce  :as coerce]
    [cljs.core.async :refer (<!)]))

(defmacro handler-fn
  "Fixes a bug in reagent were handlers cannot return 'false'
  This function wraps them to alwasys return nil instead"
  ([& body]
   `(fn [~'event] ~@body nil)))

(defn getThreads []
  (go (let [threadsResp (<! (http/get "/threads"))
            threads (:body threadsResp)]
        (swap! state/conversations-state assoc :thread-state threads))))

(defn scroll-to-bottom! [elem]
  "forces an element (div, etc) to scroll to the bottom"
  (set! (.-scrollTop elem) (- (.-scrollHeight elem) (.-offsetHeight elem))))

(defn addMessageHeaders [messagesSorted]
  (let [firstMessage (if (not= (:userId (first messagesSorted)) (:id @state/user-state))
                       (assoc (first messagesSorted) :header true)
                       (first messagesSorted))
        messages     (if (not (empty? messagesSorted))  (conj (drop 1 messagesSorted) firstMessage) )
        withHeader   (loop
                       [i 1, newList (if (not (empty? messages)) (conj '() (first messages)) '())]
                       (if (>= i (count messages))
                         (sort-by :timeStamp newList)
                         (do
                           (let [message1  (nth messages (dec i))
                                 message2   (nth messages i)
                                 userId         (:id @state/user-state)
                                 id1              (:userId message1)
                                 id2              (:userId message2)
                                 shouldAssoc? (if (and (not= id1 id2) (not= id2 userId)) true)]
                             (recur (inc i), (conj newList (if shouldAssoc? (assoc message2 :header true) message2)))))))]
    (swap! state/conversations-state assoc :messages withHeader)))

(defn getMessages [threadId idx shouldScroll?]
  "Returns first 30 messages for a thread. more messages are returned when the user scrolls up. Loops through all the messages to insert a header flag each time the speaker changes"
  (go (let [messagesResp    (<! (http/get "/messages" {:query-params  {:threadId threadId :idx idx}}))
            messagesSorted   (if (= (:threadId (first (:messages @state/conversations-state))) threadId)
                               (sort-by :timeStamp (distinct (concat (map #(dissoc % :header) (:messages @state/conversations-state)) (:body messagesResp))))
                               (sort-by :timeStamp (:body messagesResp)))]
        (swap! state/conversations-state assoc :scroll shouldScroll?)
        (addMessageHeaders messagesSorted))))

(defn createMessage! [content]
  "allows the user to create a message. content is the text of the message."
  (go (let [threadId (:threadId (:current-thread-state @state/conversations-state))
            params {:content content}
            messageResp (<! (http/post "/messages" {:edn-params params :query-params {:threadId threadId}}))]
        (reset! state/chsk (:body messageResp))
        (getMessages threadId 0 true)
        (getThreads))))

(defn createThread! [formState]
  "allows the user to create a thread."
  (go (let [title (:title formState)
            trueIds (filter (fn [[k v]] (true? v)) formState)
            ids (map #(int (clojure.string/replace (str (first %)) #":" "")) trueIds)
            params {:targetId ids :title title}
            threadResp (<! (http/post "/threads" {:edn-params params}))]
        (reset! state/chsk (:body threadResp))
        (getThreads))))

(defn updateThread! [title]
  "Allows the user to change the name of a chat."
  (go (let [threadId (:threadId (:current-thread-state @state/conversations-state))
            params {:title (if title title nil)}
            threadResp (<! (http/put (str "/threads/" threadId) {:edn-params params}))
            updatedThread (:body threadResp)
            threadId (:id updatedThread)
            title (:title updatedThread)
            targetNames (:targetUserName updatedThread)
            targetIds (:targetUserId updatedThread)]
        (getThreads)
        (swap! state/conversations-state assoc :current-thread-state {:title (if (empty? title) (apply str (interpose ", " targetNames)) title)
                                                                      :threadId threadId
                                                                      :targetUsers (zipmap targetIds targetNames)})
        (swap! state/conversations-state assoc :is-editing false))))

(defn deleteThread! []
  "Allows the user to leave a chat (not really delete)"
  (go (let [finalMessage (createMessage! (str (:nickname @state/user-state) " has left the conversation"))
            response (<! (http/delete (str "/threads/" (:threadId (:current-thread-state @state/conversations-state))) 
                                      {:query-params {:trustorId (:currentUserViewId @state/user-state)}}))]
        (if (= 200 (:status response))
          (getThreads)))))

(defn handleThreadPushEvent [data]
  (getThreads))

(defn handleMessagePushEvent [data]
  (if (= (:threadId data) (:threadId (:current-thread-state @state/conversations-state)))
    (addMessageHeaders (sort-by :timeStamp (conj (:messages @state/conversations-state) data)))))

(defn getMoreMessages []
  (let
    [elem (.getElementById js/document "messagesList")]
    (if (= 0 (.-scrollTop elem)) (getMessages (:threadId (:current-thread-state @state/conversations-state)) (count (:messages @state/conversations-state)) false))))

(defn threadItem [thread]
  "the DOM element for each thread. appears on the left pannel of the page"
  (fn [thread]
    (let [targetNames (:targetUserName thread)
          title (:title thread)
          targetIds (:targetUserId thread)
          targetEmails (:targetUserEmail thread)]
      [:li.mdl-list__item
       {:on-click (fn [] (swap! state/conversations-state assoc :current-thread-state {:title (if (empty? title) (apply str (interpose ", " targetNames)) title)
                                                                                       :threadId (:id thread)
                                                                                       :targetUsers (zipmap targetIds targetNames)})
                    (swap! state/conversations-state assoc :is-editing false)
                    (swap! state/conversations-state assoc :clearInput? true)
                    (getMessages (:id thread) 0 true))
        :class (str (if (= (:threadId (:current-thread-state @state/conversations-state)) (:id thread)) "selected ")
                    (if (not (empty? title)) "mdl-list__item--two-line"))}
       [:div
        (if (empty? title)
          [:span.mdl-list__item-primary-content
           [:span (apply str (interpose ", " targetNames))]]
          [:span.mdl-list__item-primary-content
           [:span title]
           [:span.mdl-list__item-sub-title (apply str (interpose ", " targetNames))]])]])))

(defn messageItem [message]
  "The DOM element for each message. appears on the right side of the page"
  (let [recievedMessage? (cond (>= (.indexOf (str (keys (:targetUsers (:current-thread-state @state/conversations-state)))) (:userId message)) 0) true :else false)
        ownerName (get (:targetUsers (:current-thread-state @state/conversations-state)) (:userId message))]
    (reagent/create-class
      {:component-did-mount #(if (:scroll @state/conversations-state) 
                               (scroll-to-bottom! (.getElementById js/document "messagesList")))
       :reagent-render (fn [message]
                         [:div
                          [:li.userNameLabel (if (= true (:header message)) (str ownerName))]
                          [:li.conversationsMessageContainer
                           {:class (if recievedMessage?
                                     "conversationsMessagesRecievedMessage"
                                     "conversationsMessagesSentMessage")}
                           (:content message)]])})))

(defn textInput [{:keys [title on-save on-stop ret-val isMessage?]}]
  "A new 'widget' since I didn't want the material design input.
  Stop called on 'esc' key (and after save)
  Save called on 'Enter' key
  RetVal called whenever text changes state"
  (let [val (atom title)
        stop #(let [v (-> @val str clojure.string/trim)]
                (if on-stop (on-stop v))
                (reset! val ""))
        save #(let [v (-> @val str clojure.string/trim)]
                (if-not (empty? v) (on-save v))
                (stop))
        retVal #(let [v (-> @val str clojure.string/trim)]
                  (if ret-val (ret-val v)))]
    (fn [{:keys [id class placeholder disabled clear?]}]
      (if clear? (stop))
      [:div
       [:input {:type "text" :value @val
                :id id :class class :placeholder placeholder
                :disabled disabled
                :on-focus #(swap! state/conversations-state assoc :clearInput? false)
                :on-change #(do (reset! val (-> % .-target .-value))
                              (retVal))
                :on-key-down #(case (.-which %)
                                13 (save)
                                27 (stop)
                                nil)}]
       (if isMessage?
         [:button.mdl-button.mdl-js-button.sendMessageButton
          {:on-click #(let [v (-> @val str clojure.string/trim)]
                        (if-not (empty? v)
                          (do (createMessage! v) (stop))))}
          "send"])])))

(defn checkbox [a key rowState]
  (let [elementId (str (subs (str key) 1) (:id rowState))
        classes   (if (key @a) 
                    "mdl-checkbox mdl-js-checkbox is-upgraded is-checked"
                    "mdl-checkbox mdl-js-checkbox is-upgraded")]  
    [:label {:class classes :for (str "checkbox-" elementId) :data-upgraded ",MaterialCheckbox,MaterialRipple"}
     [:input.mdl-checkbox__input {:type "checkbox" 
                                  :id  (str "checkbox-" elementId)
                                  :checked (key @a)
                                  :disabled (:disabled rowState)
                                  :on-focus  #(swap! rowState assoc :focus? true)
                                  :on-blur   #(swap! rowState assoc :focus? false)
                                  :on-change #(do (swap! a assoc key (not (key @a)))
                                                (swap! rowState assoc :dirty? (not (:dirty? @rowState))))}]
     [:span.mdl-checkbox__label (:label @rowState)]
     [:span.mdl-checkbox__focus-helper]
     [:span.mdl-checkbox__box-outline
      [:span.mdl-checkbox__tick-outline]]]))

(defn userItem [user formState]
  "Prints all of the users available to chat with.
  Inserts them in the modal."
  (fn [user formState]
    (let [checkboxState (atom {})]
      [:tr
       [:td.noBorder (checkbox formState (keyword (str (:id user))) checkboxState)]
       [:td.noBorder (:name user)]])))

(defn newThreadModal [formState]
  "A modal for creating new threads."
  (fn [formState]
    (let [availableUsers (:availableUsers @state/main-state)]
      [:div.newThreadModal.mdl-dialog
       [:div.mdl-dialog__title
        [textInput {:id "titleModalInput"
                    :class "titleModalInput"
                    :placeholder "Title your new chat!"
                    :ret-val #(swap! formState assoc :title %)
                    :on-save #(swap! formState assoc :title %)}]]
       [:div.mdl-dialog__content
        [:p "Pick users to chat with!"]
        [:table.noBorder
         [:thead
          [:tr {:style {:background-color "white !important"}}
           [:th.noBorder]
           [:th.noBorder "Name"]]]
         [:tbody
          (map (fn [user] ^{:key (str "available-user-item-" (:id user))} [userItem user formState])
               (sort-by :name availableUsers))]]]
       [:div.mdl-dialog__actions
        [:button.mdl-button.mdl-button--raised.mdl-js-button.mdl-js-ripple-effect
         {:disabled (empty? (filter true? (vals @formState)))
          :on-click #(do (createThread! @formState) 
                         (handler-fn (swap! state/conversations-state assoc :dialog-open false) nil)
                         (swap! formState assoc :title ""))}
         "Create"]
        [:button.mdl-button.mdl-js-button.warn.mdl-js-ripple-effect
         {:on-click #(do (handler-fn (swap! state/conversations-state assoc :dialog-open false) nil)
                         (swap! formState assoc :title ""))}
         "Cancel"]]])))

(defn settingsModal [settingsModalState]
  "The modal for the settings menu"
  (fn [settingsModalState]
    [:div.settingsModal.mdl-dialog
     [:div.mdl-dialog__content
      {:on-mouse-leave #(handler-fn (reset! settingsModalState false) nil)}
      [:p.settingsModalOption
       {:on-click #(do (swap! state/conversations-state assoc :is-editing true) (handler-fn (reset! settingsModalState false) nil))}
       "Edit chat title"]
      [:p.settingsModalOption
       {:on-click #(do (deleteThread!) (handler-fn (reset! settingsModalState false) nil))}
       "Leave chat"]]]))

(def titleEdit
  "A wrapper for changing the title of a thread to an editable format"
  (with-meta textInput {:component-did-mount #(.focus (reagent/dom-node %))}))

(defn conversations []
  "the main called module. Creates most of the containers"
  (let [formState (atom {})
        settingsModalState (atom false)
        searchThreads (fn [filterString]
                        (filter #(re-find (->> (str filterString)
                                               (string/upper-case)
                                               (re-pattern))
                                          (string/upper-case
                                            (str (:title %) (:targetUserName %))))
                                (:thread-state @state/conversations-state)))]
    (fn []
      [:div
       (if (:dialog-open @state/conversations-state)
         [newThreadModal formState])
       [:div.mdl-grid.messagesGrid
        [:div.mdl-cell.mdl-cell--3-col.mdl-cell--2-col-tablet.mdl-cell--1-col-phone.mdl-shadow--4dp
         [:div.messagesCardTitle
          [:button.mdl-button.mdl-js-button.mdl-button--icon.messagesAddIcon
           {:on-click (fn [] (swap! state/conversations-state assoc :dialog-open true))}
           [:i.material-icons "add"]]
          "Chats"]
         [:div.messagesCardBody
          (if (empty? (:thread-state @state/conversations-state))
            [:div.noMessages "Click the plus button to make a new chat!"]
            [:div
             [:div.threadSearch
              [textInput {:id "threadSearchInput"
                          :class "threadSearchInput"
                          :placeholder "Search"
                          :ret-val #(swap! state/conversations-state assoc :filtered-threads (searchThreads %))
                          :on-save nil}]]
             [:ul.demo-list-two.mdl-list.conversationsChatList
              (map (fn [thread] ^{:key (str "thread-item-" (:id thread))} [threadItem thread])
                   (reverse (sort-by :timeStamp (if (empty? (:filtered-threads @state/conversations-state)) 
                                                  (:thread-state @state/conversations-state) 
                                                  (:filtered-threads @state/conversations-state)))))]])]]
        [:div.mdl-cell.mdl-cell--9-col.mdl-cell--6-col-tablet.mdl-cell--3-col-phone.mdl-shadow--4dp
         (if (empty? (:current-thread-state @state/conversations-state))
           [:div.messagesCardTitle "Pick a chat to begin messaging"]
           [:div.messagesCardTitle
            (if (:is-editing @state/conversations-state)
              [titleEdit {:id "editThreadTitleInput"
                          :class "editThreadTitleInput"
                          :title (:title (:current-thread-state @state/conversations-state))
                          :placeholder "title"
                          :on-save updateThread!
                          :on-stop #(handler-fn (swap! state/conversations-state assoc :is-editing false) nil)}]
              (:title (:current-thread-state @state/conversations-state)))
            [:button.mdl-button.mdl-js-button.mdl-button--icon.threadSettingsIcon
             {:on-click #(handler-fn (reset! settingsModalState (not @settingsModalState)) nil)}
             [:i.material-icons "settings"]]
            (if @settingsModalState
              [settingsModal settingsModalState])])
         [:div.messagesCardBody
          (if (empty? (:messages @state/conversations-state))
            [:div.noMessages "No Messages!"]
            [:ul.demo-list-two.mdl-list.messagesList
             {:id "messagesList"
              :onScroll getMoreMessages}
             (map (fn [message] ^{:key (str "thread-message-item-" (:id message))} [messageItem message])
                  (sort-by :id (:messages @state/conversations-state)))])]
         [:div.sendMessagesContainer
          [:div
           [textInput {:id "messageInput"
                       :class "messageInput"
                       :placeholder "Type a message"
                       :disabled (empty? (:current-thread-state @state/conversations-state))
                       :on-save createMessage!
                       :isMessage? true
                       :clear? (:clearInput? @state/conversations-state)}]]]]]])))

(defn init []
  "init function called on login. Gets all threads for a user."
  (getThreads))