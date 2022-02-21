(def project 'thermos-importer)
(def version "0.1.0-SNAPSHOT")

(def geo-repos [["boundless" {:url "http://repo.boundlessgeo.com/main"}]
                ["central" {:url "https://repo1.maven.org/maven2/"}]])

(set-env! :resource-paths #{"resources" "src"}
          :source-paths   #{"test"}
          :repositories #(concat % geo-repos)
          :dependencies   '[[seancorfield/boot-tools-deps "0.4.7" :scope "test"]
                            [adzerk/boot-test "RELEASE" :scope "test"]
                            [org.clojure/clojure "1.10.3"]
                            [org.clojure/tools.cli "1.0.206"]
                            [org.clojure/tools.logging "1.2.2"]
                            [digest/digest "1.4.6"]
                            
                            [org.geotools/gt-main "26.1"]
                            [org.geotools/gt-shapefile "26.1"]
                            [org.geotools/gt-coverage "26.1"]
                            [org.geotools/gt-geotiff "26.1"]
                            [org.geotools/gt-geojson "26.1"]
                            [org.geotools/gt-geopkg "26.1"]
                            [org.geotools/gt-referencing "26.1"] ; Coordinate transformations
                            [org.geotools/gt-epsg-wkt "26.1"] ; Coordinate system definitions
                            
                            [com.github.davidmoten/rtree "0.8.7"]
                            [org.clojure/data.csv "1.0.0"]
                            [org.clojure/data.json "2.4.0"]
                            [clj-http/clj-http "3.9.0"]
                            
                            [better-cond/better-cond "1.0.1"]

                            ])

(require '[boot-tools-deps.core :refer [deps]])

(task-options!
 aot {:namespace   #{'thermos-importer.core}}
 pom {:project     project
      :version     version
      :description "FIXME: write description"
      :url         "http://example/FIXME"
      :scm         {:url "https://github.com/yourname/thermos-importer"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:main        'thermos-importer.core
      :file        (str "thermos-importer-" version "-standalone.jar")
      :manifest    {"Specification-Title" "Java Advanced Imaging Image I/O Tools"
                    "Specification-Version" "1.1"
                    "Specification-Vendor" "Sun Microsystems, Inc."
                    "Implementation-Title" "com.sun.media.imageio"
                    "Implementation-Version" "1.1"
                    "Implementation-Vendor" "Sun Microsystems, Inc."}
      })

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp ;; (deps :overwrite-boot-deps true)

          (aot) (pom) (uber) (jar) (target :dir dir))))

(require '[adzerk.boot-test :refer [test]])
