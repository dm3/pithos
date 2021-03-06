(ns io.pithos.meta
  "The metastore is region-local and stores details of bucket content
   (bucket contents are region-local as well)."
  (:require [qbits.alia      :refer [execute]]
            [qbits.hayt      :refer [select where set-columns columns
                                     delete update limit map-type
                                     create-table column-definitions
                                     create-index index-name]]
            [io.pithos.util  :refer [inc-prefix]]
            [io.pithos.store :as store]))

(defprotocol Metastore
  "All necessary functions to manipulate bucket metadata"
  (converge! [this])
  (fetch [this bucket object] [this bucket object fail?])
  (prefixes [this bucket params])
  (finalize! [this bucket key version size checksum])
  (update! [this bucket object columns])
  (delete! [this bucket object])
  (abort-multipart-upload! [this bucket object upload])
  (update-part! [this bucket object upload partno columns])
  (initiate-upload! [this bucket object upload metadata])
  (list-uploads [this bucket])
  (list-object-uploads [this bucket object])
  (list-upload-parts [this bucket object upload]))

;; schema definition

(def object-table
  "Objects are keyed by bucket and object and contain
   several direct properties as well as a map of additional
   schema-less properties"
  (create-table
  :object
  (column-definitions {:bucket       :text
                       :object       :text
                       :inode        :uuid
                       :version      :timeuuid
                       :atime        :text
                       :size         :bigint
                       :checksum     :text
                       :storageclass :text
                       :acl          :text
                       :metadata     (map-type :text :text)
                       :primary-key  [:bucket :object]})))

(def object_inode-index
  "Objects are indexed by inode"
  (create-index
   :object
   :inode
   (index-name :object_inode)))

(def upload-table
  "Uploads are keyed by bucket, object and upload since several concurrent
   uploads can be performed"
 (create-table
  :upload
  (column-definitions {:upload      :uuid
                       :version     :uuid
                       :bucket      :text
                       :object      :text
                       :inode       :uuid
                       :partno      :int
                       :modified    :text
                       :size        :bigint
                       :checksum    :text
                       :primary-key [[:bucket :object :upload] :partno]})))

(def upload_bucket-index
  "Uploads are indexed by bucket for easy lookup"
  (create-index
   :upload
   :bucket
   (index-name :upload_bucket)))

(def object_uploads-table
  "Uploads are also referenced by object"
 (create-table
  :object_uploads
  (column-definitions {:bucket      :text
                       :object      :text
                       :upload      :uuid
                       :metadata    (map-type :text :text)
                       :primary-key [[:bucket :object] :upload]})))


;; CQL Queries

;; Note: Possible improvements, all of these are preparable, with the
;; exception of initiate-upload-q and update-part-q (unless we freeze
;; the fields to update making them parameters). A function taking a
;; session that returns a map of prepared queries could be invoked from
;; cassandra-meta-store, destructured in a let via {:keys [...]} then
;; used with execute in that scope.

(defn abort-multipart-upload-q
  "Delete an upload reference"
  [bucket object upload]
  (delete :object_uploads (where [[= :bucket bucket]
                                  [= :object object]
                                  [= :upload upload]])))

(defn delete-upload-parts-q
  "Delete all upload parts"
  [bucket object upload]
  (delete :upload (where [[= :bucket bucket]
                          [= :object object]
                          [= :upload upload]])))

(defn initiate-upload-q
  "Create an upload reference"
  [bucket object upload metadata]
  (update :object_uploads
          (set-columns {:metadata metadata})
          (where [[= :bucket bucket]
                  [= :object object]
                  [= :upload upload]])))

(defn update-part-q
  "Update an upload part's properties"
  [bucket object upload partno columns]
  (update :upload
          (set-columns columns)
          (where [[= :bucket bucket]
                  [= :object object]
                  [= :upload upload]
                  [= :partno partno]])))

(defn list-uploads-q
  "List all uploads by bucket"
  [bucket]
  (select :upload (where [[= :bucket bucket]])))

(defn list-upload-parts-q
  "List all parts of an upload"
  [bucket object upload]
  (select :upload (where [[= :bucket bucket]
                          [= :object object]
                          [= :upload upload]])))

(defn list-object-uploads-q
  "List all uploads of an object"
  [bucket object]
  (select :object_uploads (where [[= :bucket bucket]
                                  [= :object object]])))

(defn fetch-object-q
  "List objects"
  [bucket prefix]
  (let [object-def  [[= :bucket bucket]]
        next-prefix (inc-prefix prefix)
        prefix-def  [[>= :object prefix]
                     [< :object next-prefix]]]
    (select :object (where (cond-> object-def
                                   (seq prefix) (concat prefix-def))))))

(defn get-object-q
  "Fetch object properties"
  [bucket object]
  (select :object
          (where [[= :bucket bucket]
                  [= :object object]])
          (limit 1)))

(defn update-object-q
  "Update object properties"
  [bucket object columns]
  (update :object
          (set-columns columns)
          (where [[= :bucket bucket]
                  [= :object object]])))

(defn delete-object-q
  "Delete an object"
  [bucket object]
  (delete :object (where [[= :bucket bucket]
                          [= :object object]])))

;; ### Utility functions

(defn filter-content
  "Keep only contents in a list of objects"
  [objects prefix delimiter]
  (let [pat (re-pattern (str "^" prefix "[^\\" delimiter "]*$"))]
    (filter (comp (partial re-find pat) :object) objects)))

(defn filter-prefixes
  "Keep only prefixes from a list of objects"
  [objects prefix delimiter]
  (let [pat (re-pattern
             (str "^(" prefix "[^\\" delimiter "]*\\" delimiter ").*$"))]
    (->> (map (comp second (partial re-find pat) :object) objects)
         (remove nil?)
         (set))))

(defn cassandra-meta-store
  "Given a cluster configuration, reify an instance of Metastore"
  [config]
  (let [session (store/cassandra-store config)]
    (reify Metastore
      (converge! [this]
        (execute session object-table)
        (execute session upload-table)
        (execute session object_uploads-table)
        (execute session object_inode-index)
        (execute session upload_bucket-index))
      (fetch [this bucket object fail?]
        (or
         (first (execute session (get-object-q bucket object)))
         (when fail?
           (throw (ex-info "no such key" {:type :no-such-key
                                          :status-code 404
                                          :key object})))))
      (fetch [this bucket object]
        (fetch this bucket object true))
      (prefixes [this bucket {:keys [prefix delimiter max-keys]}]
        (let [objects  (execute session (fetch-object-q bucket prefix))
              prefixes (if delimiter
                         (filter-prefixes objects prefix delimiter)
                         #{})
              contents (if delimiter
                         (filter-content objects prefix delimiter)
                         objects)]
         [(remove prefixes contents) prefixes]))
      (initiate-upload! [this bucket object upload metadata]
        (execute session (initiate-upload-q bucket object upload metadata)))
      (abort-multipart-upload! [this bucket object upload]
        (execute session (abort-multipart-upload-q bucket object upload))
        (execute session (delete-upload-parts-q bucket object upload)))
      (update-part! [this bucket object upload partno columns]
        (execute session (update-part-q bucket object upload partno columns)))
      (list-uploads [this bucket]
        (execute session (list-uploads-q bucket)))
      (list-object-uploads [this bucket object]
        (execute session (list-object-uploads-q bucket object)))
      (list-upload-parts [this bucket object upload]
        (execute session (list-upload-parts-q bucket object upload)))
      (update! [this bucket object columns]
        (execute session (update-object-q bucket object columns)))
      (delete! [this bucket object]
        (execute session (delete-object-q bucket object))))))
