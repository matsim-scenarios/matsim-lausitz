
N := lausitz
V := v1.0
CRS := EPSG:25832

JAR := matsim-$(N)-*.jar

export SUMO_HOME := $(abspath ../../sumo-1.15.0/)
osmosis := osmosis\bin\osmosis

.PHONY: prepare

#$(JAR):
#	mvn package

# Required files
input/network.osm.pbf:
	curl https://download.geofabrik.de/europe/germany-230101.osm.pbf -o input/network.osm.pbf

# Bast count data
input/2019_A_S.zip:
	curl https://www.bast.de/videos/2019_A_S.zip -o $@

input/2019_B_S.zip:
	curl https://www.bast.de/videos/2019_B_S.zip -o $@

input/Jawe2019.csv:
	curl "https://www.bast.de/DE/Verkehrstechnik/Fachthemen/v2-verkehrszaehlung/Daten/2019_1/Jawe2019.csv?view=renderTcDataExportCSV&cms_strTyp=A" -o $@

input/network.osm: input/network.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="../shared-svn/projects/DiTriMo/data/cottbus.poly"\
	 --used-node --wb input/network-detailed.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction\
	 --bounding-polygon file="../shared-svn/projects/DiTriMo/data/lausitz.poly"\
	 --used-node --wb input/network-coarse.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
	 --used-node --wb input/network-germany.osm.pbf

	$(osmosis) --rb file=input/network-germany.osm.pbf --rb file=input/network-coarse.osm.pbf --rb file=input/network-detailed.osm.pbf\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm input/network-detailed.osm.pbf
	rm input/network-coarse.osm.pbf
	rm input/network-germany.osm.pbf


input/sumo.net.xml: input/network.osm

	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\
	 --osm-files $< -o=$@


input/$V/$N-$V-network.xml.gz: input/sumo.net.xml
	java -jar $(JAR) prepare network-from-sumo $<\
	 --output $@

	java -jar $(JAR) prepare clean-network $@ --output $@ --modes car --modes bike


input/$V/$N-$V-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	java -Xmx20G -jar $(JAR) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2023-01-11" --target-crs $(CRS) \
	 ../shared-svn/projects/DiTriMo/data/gtfs/20230113_regio.zip\
	 ../shared-svn/projects/DiTriMo/data/gtfs/20230113_train_short.zip\
	 ../shared-svn/projects/DiTriMo/data/gtfs/20230113_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp ../shared-svn/projects/DiTriMo/data/network-area/network-area.shp\
	 --shp ../shared-svn/projects/DiTriMo/data/network-area/network-area.shp\
	 --shp ../shared-svn/projects/DiTriMo/data/germany-area/germany-area.shp\

input/freight-trips.xml.gz: input/$V/$N-$V-network.xml.gz
	java -jar $(JAR) prepare extract-freight-trips ../shared-svn/projects/german-wide-freight/v1.2/german-wide-freight-25pct.xml.gz\
	 --network ../shared-svn/projects/german-wide-freight/original-data/german-primary-road.network.xml.gz\
	 --input-crs EPSG:5677\
	 --target-crs $(CRS)\
	 --shp ../shared-svn/projects/DiTriMo/data/shp/$N.shp --shp-crs $(CRS)\
	 --output $@

input/$V/prepare-100pct.plans.xml.gz:
	java -jar $(JAR) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/$V\
	 --population ../shared-svn/projects/DiTriMo/matsim-input-files/senozon/20230111_teilmodell_lausitz/population.xml.gz\
	 --attributes  ../shared-svn/projects/DiTriMo/matsim-input-files/senozon/20230111_teilmodell_lausitz/additionalPersonAttributes.xml.gz

	java -jar $(JAR) prepare resolve-grid-coords\
	 input/$V/prepare-100pct.plans.xml.gz\
	 --input-crs $(CRS)\
	 --grid-resolution 300\
	 --landuse ../matsim-leipzig/scenarios/input/landuse/landuse.shp\
	 --output $@

input/$V/$N-$V-100pct.plans.xml.gz: input/freight-trips.xml.gz input/$V/prepare-100pct.plans.xml.gz

	java -jar $(JAR) prepare generate-short-distance-trips\
 	 --population input/$V/prepare-100pct.plans.xml.gz\
 	 --input-crs $(CRS)\
	 --shp ../shared-svn/projects/DiTriMo/data/shp/$N.shp --shp-crs $(CRS)\
 	 --num-trips 1 # TODO

	java -jar $(JAR) prepare adjust-activity-to-link-distances input/$V/prepare-100pct.plans-with-trips.xml.gz\
	 --shp ../shared-svn/projects/DiTriMo/data/shp/$N.shp --shp-crs $(CRS)\
     --scale 1.15\
     --input-crs $(CRS)\
     --network input/$V/$N-$V-network.xml.gz\
     --output input/$V/prepare-100pct.plans-adj.xml.gz

	java -jar $(JAR) prepare fix-subtour-modes --coord-dist 100 --input input/$V/prepare-100pct.plans-adj.xml.gz --output $@

	java -jar $(JAR) prepare merge-populations $@ $< --output $@

	java -jar $(JAR) prepare extract-home-coordinates $@ --csv input/$V/$N-$V-homes.csv

	java -jar $(JAR) prepare downsample-population $@\
    	 --sample-size 1\
    	 --samples 0.25 0.01\

input/$V/$N-$V-counts-car-bast.xml.gz: input/2019_A_S.zip input/2019_B_S.zip input/Jawe2019.csv input/$V/$N-$V-network-with-pt.xml.gz

	java -Xmx20G -jar $(JAR) prepare counts-from-bast\
		--network input/$V/$N-$V-network-with-pt.xml.gz\
		--motorway-data input/2019_A_S.zip\
		--primary-data input/2019_B_S.zip\
		--station-data input/Jawe2019.csv\
		--year 2019\
		 --shp ../shared-svn/projects/DiTriMo/data/shp/$N.shp --shp-crs $(CRS)\
		--car-output $@\
		--freight-output $(subst car,freight,$@)

check: input/$V/$N-$V-100pct.plans.xml.gz
	java -jar $(JAR) analysis commuter\
	 --population $<\
 	 --input-crs $(CRS)\
	 --shp ../shared-svn/projects/DiTriMo/data/vg5000/vg5000_ebenen_0101/VG5000_VWG.shp\
	 --attr GEN\
	 --output input/$N-$V-commuter.csv

	java -jar $(JAR) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp ../shared-svn/projects/DiTriMo/data/shp/$N.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/$V/$N-$V-100pct.plans.xml.gz input/$V/$N-$V-network-with-pt.xml.gz input/$V/$N-$V-counts-car-bast.xml.gz
	echo "Done"