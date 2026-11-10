package org.matsim.run.analysis;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;
import tech.tablesaw.api.*;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.util.*;

import static org.matsim.run.analysis.LausitzDrtAnalysis.*;
import static tech.tablesaw.aggregate.AggregateFunctions.count;

@CommandLine.Command(name = "mode-share", description = "Analysis quickly written for DiTriMo Abschlussveranstaltung. Probably will not be used in future.")

public class ModeShareInDrtServiceAreaAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--trips", description = "Path to trips file", required = true)
	private String tripsFile;
	@CommandLine.Option(names = "--output", description = "Path to output dir. Has to end with slash.", required = true)
	private String output;
	@CommandLine.Option(names = "--dist-groups", split = ",", description = "List of distances for binning", defaultValue = "0,1000,2000,5000,10000,20000")
	private List<Double> distGroups;
	@CommandLine.Mixin
	private ShpOptions shp;



	public static void main(String[] args) {
		new ModeShareInDrtServiceAreaAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, EUCL_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING));

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsFile))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsFile)).build());

		Table freightTrips = trips.where(trips.stringColumn(TRIP_ID).containsString("commercialPersonTraffic")
			.or(trips.stringColumn(TRIP_ID).containsString("freight"))
			.or(trips.stringColumn(TRIP_ID).containsString("goodsTraffic")));

		Table tripsWithoutFreight = trips.where(trips.stringColumn(TRIP_ID).isNotIn(freightTrips.stringColumn(TRIP_ID)));

		List<String> drtServiceAreaTripIds = new ArrayList<>();
		Geometry geometry = shp.getGeometry();

//		filter for trips which start or end in service area
		for (int i = 0; i < tripsWithoutFreight.rowCount(); i++) {
			Row row = tripsWithoutFreight.row(i);

			Coord startCoord = new Coord(row.getDouble("start_x"), row.getDouble("start_y"));
			Coord endCoord = new Coord(row.getDouble("end_x"), row.getDouble("end_y"));

			if (MGC.coord2Point(startCoord).within(geometry) || MGC.coord2Point(endCoord).within(geometry)) {
				drtServiceAreaTripIds.add(row.getText(TRIP_ID));
			}
		}

		Table drtServiceAreaTrips =tripsWithoutFreight.where(tripsWithoutFreight.stringColumn(TRIP_ID).isIn(drtServiceAreaTripIds));

		//		write all trips in drt service area to csv
		drtServiceAreaTrips.write().csv(output + "trips_in_drt_service_area.csv");

		List<String> labels = new ArrayList<>();
		for (int i = 0; i < distGroups.size() - 1; i++) {
			labels.add(String.format("%d - %d", distGroups.get(i).intValue(), distGroups.get(i + 1).intValue()));
		}
		labels.add(distGroups.getLast() + "+");
		distGroups.add(Double.MAX_VALUE);

		StringColumn distGroup = drtServiceAreaTrips.doubleColumn(TRAV_DIST)
			.map(dist -> cut(dist, distGroups, labels), ColumnType.STRING::create).setName(DIST_GROUP);

		drtServiceAreaTrips.addColumns(distGroup);

		Table aggr = drtServiceAreaTrips.summarize(TRIP_ID, count).by(DIST_GROUP, MAIN_MODE);

		DoubleColumn share = aggr.numberColumn(2).divide(aggr.numberColumn(2).sum()).setName(SHARE);
		aggr.replaceColumn(2, share);

		// Sort by dist_group and mode
		Comparator<Row> cmp = Comparator.comparingInt(row -> labels.indexOf(row.getString(DIST_GROUP)));
		aggr = aggr.sortOn(cmp.thenComparing(row -> row.getString(MAIN_MODE)));

		aggr.write().csv(output + "mode_share_in_drt_service_area.csv");

		return 0;
	}
}
