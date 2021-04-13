;; This file is part of THERMOS, copyright © Centre for Sustainable Energy, 2017-2021
;; Licensed under the Reciprocal Public License v1.5. See LICENSE for licensing details.

(ns thermos-importer.lidar
  (:require [thermos-importer.geoio :as geoio]
            [thermos-importer.spatial :as spatial]
            [thermos-importer.util :as util]
            [clojure.tools.logging :as log])
  (:import com.github.davidmoten.rtree.geometry.Geometries
           com.github.davidmoten.rtree.RTree
           [org.locationtech.jts.geom Coordinate GeometryFactory Polygon]
           [org.locationtech.jts.geom.util LineStringExtracter]
           org.geotools.coverage.grid.GridCoverage2D
           org.geotools.coverage.grid.io.GridFormatFinder
           org.geotools.factory.Hints
           org.geotools.gce.geotiff.GeoTiffFormat
           org.geotools.geometry.DirectPosition2D
           org.geotools.geometry.jts.ReferencedEnvelope
           org.geotools.geometry.jts.JTS
           org.geotools.referencing.CRS))

(def ^:dynamic *storey-height* 3.0)

(defn load-raster* [raster]
  (let [format (GridFormatFinder/findFormat raster)
        hints (when (instance? GeoTiffFormat format)
                (Hints. Hints/FORCE_LONGITUDE_FIRST_AXIS_ORDER true))
        reader (.getReader format raster hints)]
    (.read reader nil)))


(let [load-it (util/soft-memoize load-raster*)]
  (defn ^GridCoverage2D load-raster [raster]
    (load-it raster)))

; we memoize these fully,
; since the outputs are small
; and this gets called a lot.
(def raster-facts
  (memoize
   (fn [raster]
     (log/info "Load summary information for" raster)
     (let [raster-data (load-raster raster)

           envelope (.getEnvelope2D raster-data)
           bounds  (Geometries/rectangle
                    (.getMinimum envelope 0) (.getMinimum envelope 1)
                    (.getMaximum envelope 0) (.getMaximum envelope 1))

           crs (CRS/lookupIdentifier (.getCoordinateReferenceSystem2D raster-data) true)]
           ;; the thing we return is just the filename & summary. if
           ;; we really need the raster's data later we will load it
           ;; again and hopefully hit a cache in the process.
       {:raster raster :bounds  bounds :crs crs}))))

(defn rasters->index
    "Make an index which says which of these rasters (filenames) is where.
  The index is a map from EPSG code to an Rtree of rasters that have that EPSG.
  "
    [rasters]
    (log/info "Indexing rasters...")
    
    (let [properties                      ; first lookup the properties for each raster
          (map raster-facts rasters)
          
          by-crs                          ; bin them by CRS
          (group-by :crs properties)

          indices                         ; for each CRS, stuff them into an Rtree
          (for [[crs rasters] by-crs]
            [crs
             (reduce
              (fn [index {raster :raster bounds :bounds}]
                (.add index raster bounds))
              (RTree/create) rasters)])
          ]
      (into {} indices)))

(defn- find-rasters
  "Locate all the rasters that overlap the bounds of shape."
  [tree rect]
  (util/search-rtree tree rect))

(defn- sample-coords
  "Sample coordinates within shape from raster.
  Presumes coords are in the raster's CRS."
  [raster coords]
  (let [raster ^GridCoverage2D (load-raster raster)
        no-data (set (.getNoDataValues (.getSampleDimension raster 0)))]

    (filter
     identity
     (for [[x y] coords]
       (let [position ^DirectPosition2D (DirectPosition2D. x y)
             result (try
                      (.evaluate raster position)
                      (catch org.opengis.coverage.PointOutsideCoverageException e
                        nil))
             z (and result (aget result 0))]
         (when (and z (not (no-data z)))
           [x y z]))))))

(defn- summarise
  "Approximately summarise the building from this set of x/y/z values."
  [shape coords ground-level-threshold]
  (if (not (empty? coords))
    (let [perimeter (.getLength shape)

          heights (map last coords)
          heights (filter #(> % ground-level-threshold) heights)
          
          ground (if (seq heights)
                   (apply min heights)
                   0)
          
          heights (map #(- % ground) heights)

          heights (filter #(> % 0.5) heights)
          mean-height (if (empty? heights)
                        0
                        (/ (apply + heights) (count heights)))

          footprint (.getArea shape)
          ]
      {::perimeter perimeter
       ::footprint footprint
       ::ground-height ground
       ::height mean-height
       ::num-samples (count heights)
       })
    {::num-samples 0}))

(defn- grid-over
  "Make a seq of coordinates covering the SHAPE with a buffer of 1m

  TODO: make faster; delaunay triangulation + area weighted choice of
  triangle + uniform random point inside triangle

  http://mathworld.wolfram.com/TrianglePointPicking.html
  "
  [shape buffer-size]
  (let [shape (.buffer shape buffer-size)
        
        envelope (.getEnvelopeInternal shape)
        x-min (.getMinX envelope)
        x-max (.getMaxX envelope)
        y-min (.getMinY envelope)
        y-max (.getMaxY envelope)


        extent (max (Math/abs (- x-max x-min)) (Math/abs (- y-max y-min)))
        
        grid-step (max 1.0 (Math/round (/ extent 50.0)))
        ]

    (for [x (range x-min x-max grid-step)
          y (range y-min y-max grid-step)
          :when (.covers shape
                         (.createPoint
                          (.getFactory shape)
                          (Coordinate. x y)))]
      [x y])))

(defn shape->dimensions
  "Given an rtree for a set of rasters, and a shape which is a JTS geometry.

  Presumes the shape has been projected into the CRS for all the rasters in the rtree.

  ::surface-area
  ::volume
  ::floor-area"
  [tree shape buffer-size ground-level-threshold]

  (let [rect    (util/geom->rect shape)
        rasters (find-rasters tree rect)
        grid    (grid-over shape buffer-size)
        coords  (mapcat #(sample-coords % grid) rasters)]
    (summarise shape coords ground-level-threshold)))

(defn estimate-party-walls [feature index]
  (try
    (let [geom (::geoio/geometry feature)
          rect (util/geom->rect geom)
          neighbours (util/search-rtree index rect)
          perimeter (.getLength geom)

          boundary (.getBoundary geom)

          inter-bounds (for [n neighbours :when (not= n feature)]
                         (let [n-boundary (.getBoundary (::geoio/geometry n))]
                           (LineStringExtracter/getGeometry
                            (.intersection boundary n-boundary))))
          
          ]
      (if (seq inter-bounds)
        (let [party-bounds (reduce
                            (fn [a b] (.union a b))
                            inter-bounds)
              party-perimeter (.getLength party-bounds)

              party-perimeter-proportion (/ party-perimeter perimeter)]
          (assoc feature ::shared-perimeter party-perimeter-proportion))
        
        (assoc feature ::shared-perimeter 0)))
    (catch Exception e
      (log/error e
                 "Error computing party walls for %s: %s\n"
                 (dissoc feature ::geoio/geometry)
                 (.getMessage e))
      (assoc feature ::shared-perimeter 0))))

(defn- derive-2d-fields [feature]
  (let [{shared-perimeter ::shared-perimeter
         perimeter ::perimeter
         footprint ::footprint} feature

        perimeter (or perimeter 0)
        footprint (or footprint 0.1)

        shared-perimeter (or shared-perimeter 0)

        ;; in meteres
        shared-perimeter-m (* shared-perimeter perimeter)

        perimeter-per-footprint (if (zero? perimeter)
                                  0 (/ perimeter footprint))
        
        ]
    (assoc feature
           ::shared-perimeter-m shared-perimeter-m
           ::perimeter-per-footprint perimeter-per-footprint)))

(defn- derive-3d-fields [feature]
  (try
    (let [{shared-perimeter ::shared-perimeter
           perimeter ::perimeter
           height ::height
           floor-area ::floor-area
           footprint ::footprint} feature

          wall-area (* perimeter height)

          party-wall-area (* shared-perimeter wall-area)
          external-wall-area (- wall-area party-wall-area)
          external-surface-area (+ external-wall-area (* 2 footprint))
          total-surface-area (+ wall-area (* 2 footprint))

          volume (* footprint height)

          ext-surface-proportion (/ external-surface-area total-surface-area)
          ext-surface-per-volume (/ external-surface-area volume)
          
          tot-surface-per-volume (/ total-surface-area volume)
          ]
      (assoc feature
             ::wall-area wall-area
             ::party-wall-area party-wall-area
             ::external-wall-area external-wall-area
             ::external-surface-area external-surface-area
             ::total-surface-area total-surface-area
             ::volume volume
             ::ext-surface-proportion ext-surface-proportion
             ::ext-surface-per-volume ext-surface-per-volume
             ::tot-surface-per-volume tot-surface-per-volume))

    (catch ArithmeticException e
      (log/error e "deriving-3d-fields" (pr-str (dissoc feature ::geoio/geometry)))
      (throw e))))

(defn derive-more-fields [feature]
  ;; take or compute storeys ceil(height / storey height)
  ;; take or compute floor area (storeys * footprint)
  ;; derive other fields
  ;; if we have both storeys and floor area, wargarbl?

  (let [height  (::height feature)
        
        storeys (max 1
                     (or (::storeys feature)
                         ;; should we round up here?
                         (and height (int (Math/floor (/ height *storey-height*))))
                         1))

        height     (or height
                       ;; this might be bogus - if we know the storeys and not height
                       ;; we derive a height
                       (and storeys (* storeys *storey-height*)))
        
        floor-area (or (::floor-area feature)
                       (* (::footprint feature 0) (or storeys 1)))

        shared-perimeter (::shared-perimeter feature)
        ]
    
    (cond-> feature
      storeys    (assoc ::storeys storeys)
      height     (assoc ::height height)
      floor-area (assoc ::floor-area floor-area)
      shared-perimeter (derive-2d-fields)
      (and shared-perimeter height (pos? height)
           (pos? (::footprint feature 0)))
      (derive-3d-fields))))

(defn envelope-covers-tree [raster-crs raster-tree
                            shapes-crs ^org.locationtech.jts.geom.Envelope shapes-envelope]

  (let [raster-mbr (.mbr raster-tree)]
    (when (.isPresent raster-mbr)
      (let [raster-mbr (.get raster-mbr)
            raster-crs (CRS/decode raster-crs true)
            transform (CRS/findMathTransform shapes-crs raster-crs)
            shapes-envelope (JTS/transform shapes-envelope transform)
            shapes-envelope (util/envelope->rect shapes-envelope)]
        (.intersects raster-mbr shapes-envelope)))))

(defn count-corners [geom]
  (case (.getGeometryType geom)
    ("Polygon" "MultiPolygon")
    (let [geom (org.locationtech.jts.simplify.TopologyPreservingSimplifier/simplify geom 7.5)]
      (dec (.getNumPoints geom)))
    0))

(defn add-lidar-to-shapes
  "Given a raster index from `rasters->index` and a `shapes`, which is a
  geoio feature thingy i.e. a map with ::geoio/crs
  and ::geoio/features in it

  return an updated `shapes`, in which the features have
  got ::lidar/surface-area etc. from shape->dimensions."
  [shapes index & {:keys [buffer-size ground-level-threshold]
                   :or {buffer-size 1.5
                        ground-level-threshold -5}}]

  (log/info (count (::geoio/features shapes)) "shapes to lidarize")
  
  (let [shapes-crs (::geoio/crs shapes)
        shapes-crs* (CRS/decode shapes-crs true)
        
        feature-index (util/index-features (::geoio/features shapes))
        sensible-transform (spatial/sensible-projection
                            :utm-zone
                            shapes-crs (::geoio/geatures shapes))

        shapes-box  (geoio/bounding-box shapes)
        index       (filter (fn [[raster-crs raster-tree]]
                              (envelope-covers-tree raster-crs raster-tree
                                                    shapes-crs* shapes-box))
                            index)
        
        add-footprint-and-perimeter (fn [feature]
                                      (let [shape (::geoio/geometry feature)
                                            shape (JTS/transform shape sensible-transform)]
                                        (merge feature
                                               {::num-samples 0
                                                ::footprint (.getArea shape)
                                                ::perimeter (.getLength shape)
                                                ::corners   (count-corners shape)
                                                })))
        ]

    (log/info (reduce + (map #(.size (second %)) index)) "tiles to match against")
    
    (as-> shapes shapes
      (geoio/update-features shapes :estimate-party-walls estimate-party-walls feature-index)
      (geoio/update-features shapes :footprint-and-perimeter add-footprint-and-perimeter)
      ;; remove any polygonal shapes with zero footprint
      (update shapes ::geoio/features #(filter (fn [f]
                                                 (or
                                                  (pos? (::footprint f))
                                                  (not= :polygon (::geoio/type f))))
                                               %))

      (if index
        ;; mangle shapes
        (reduce
         (fn [shapes [raster-crs raster-tree]]
           (let [transform (CRS/findMathTransform shapes-crs* (CRS/decode raster-crs true))]
             (geoio/update-features
              shapes :intersect-with-lidar
              (fn [feature]
                (if (= :polygon (::geoio/type feature))
                  (merge
                   (try
                     (shape->dimensions
                      raster-tree
                      (JTS/transform (::geoio/geometry feature) transform)
                      
                      buffer-size
                      ground-level-threshold)
                     (catch Exception e
                       (log/error
                        e "Error adding lidar data to"
                        (dissoc feature ::geoio/geometry))
                       {}))

                   feature)

                  ;; pass points through
                  feature)))))
         shapes index)
        
        ;; do nothing
        shapes)
      
      (geoio/update-features shapes :derive-fields derive-more-fields))))
