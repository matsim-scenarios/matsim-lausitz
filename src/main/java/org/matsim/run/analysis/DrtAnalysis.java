package org.matsim.run.analysis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.lang3.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvReadOptions;
import tech.tablesaw.selection.Selection;

import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;
import static tech.tablesaw.aggregate.AggregateFunctions.*;

@CommandLine.Command(name = "drt", description = "Analyze and compare agents who use new drt service from " +
	" policy case and the respective trips in the base case..")
@CommandSpec(requireRunDirectory = true,
	produces = {"drt_persons.csv", "drt_persons_home_locations.csv", "drt_persons_income_groups.csv", "drt_persons_age_groups.csv",
		"mean_travel_stats.csv", "drt_persons_trav_time.csv", "drt_persons_traveled_distance.csv", "drt_persons_base_modal_share.csv",
		"drt_persons_mean_score_per_income_group.csv", "drt_persons_executed_score.csv", "all_persons_income_groups.csv", "all_persons_age_groups.csv",
		"trips_in_drt_service_area.csv.gz", "mode_share.csv", "mode_share_per_dist.csv", "drt_legs_zones_od.csv"
	}
)

public class DrtAnalysis implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(DrtAnalysis.class);

	@CommandLine.Mixin
	private final InputOptions input = InputOptions.ofCommand(DrtAnalysis.class);
	@CommandLine.Mixin
	private OutputOptions output = OutputOptions.ofCommand(DrtAnalysis.class);
	@CommandLine.Option(names = "--income-groups", split = ",", description = "List of income for binning", defaultValue = "0,500,900,1500,2000,3000,4000,5000,6000,7000")
	private List<Integer> incomeGroups;
	@CommandLine.Option(names = "--age-groups", split = ",", description = "List of age for binning", defaultValue = "0,18,30,50,70")
	private List<Integer> ageGroups;
	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;
	@CommandLine.Option(names = "--dist-groups", split = ",", description = "List of distances for binning", defaultValue = "0,1000,2000,5000,10000,20000")
	private List<Double> distGroups;

	private static final String INCOME_GROUP = "incomeGroup";
	private static final String PERSON = "person";
	private static final String SHARE = "share";
	private static final String SCORE = "executed_score";
	private static final String INCOME = "income";
	private static final String TRAV_TIME = "trav_time";
	private static final String TRAV_DIST = "traveled_distance";
	private static final String EUCL_DIST = "euclidean_distance";
	private static final String MAIN_MODE = "main_mode";
	private static final String TRIP_ID = "trip_id";
	private static final String BASE_SUFFIX = "_base";
	private static final String DIST_GROUP = "dist_group";
	private static final String DEPARTURE_H = "departureHour";
	private static final String DEPARTURE_TIME = "departureTime";
	private static final String PERSON_ID = "personId";
	private static final String DRT_PREFIX = "drt_persons_";
	private static final String ORIG_ZONE_ID = "originZoneId";
	private static final String DEST_ZONE_ID = "destinationZoneId";

	public static void main(String[] args) {
		new DrtAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
//		create pt line analysis object to use handy methods
		PtLineAnalysis ptLineAnalysis = new PtLineAnalysis(incomeGroups, ageGroups, output);

//		all necessary file input paths are defined here
		String personsPath = globFile(input.getRunDirectory(), "*output_persons.csv.gz").toString();
		String tripsPath = globFile(input.getRunDirectory(), "*output_trips.csv.gz").toString();
		String drtLegsPath = globFile(input.getRunDirectory(), "*output_drt_legs_drt.csv").toString();
		String configPath = globFile(input.getRunDirectory(), "*output_config.xml").toString();
		String basePersonsPath = globFile(basePath, "*output_persons.csv.gz").toString();
		String baseTripsPath = globFile(basePath, "*output_trips.csv.gz").toString();

		Table persons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(personsPath)).build());

		Map<String, Range<Integer>> incomeLabels = ptLineAnalysis.getLabels(incomeGroups);
		incomeLabels.put(incomeGroups.getLast() + "+", Range.of(incomeGroups.getLast(), 9999999));
		incomeGroups.add(Integer.MAX_VALUE);

//		filter for real agents only, no freight agents!
		Table freightPersons = persons.where(persons.textColumn(PERSON).containsString("commercialPersonTraffic")
			.or(persons.textColumn(PERSON).containsString("freight"))
			.or(persons.textColumn(PERSON).containsString("goodsTraffic")));
		persons = persons.where(persons.textColumn(PERSON).isNotIn(freightPersons.textColumn(PERSON)));

		//		add income group column to persons table for further analysis
		persons = ptLineAnalysis.addIncomeGroupColumnToTable(persons, incomeLabels);

//		write general income and age distr
		ptLineAnalysis.writeIncomeDistr(persons, incomeLabels, "all_persons_income_groups.csv", null);
		ptLineAnalysis.writeAgeDistr(persons, "all_persons_age_groups.csv", null);

		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, EUCL_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING));


		Table drtLegs = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(drtLegsPath))
			.columnTypesPartial(Map.of(DEPARTURE_TIME, ColumnType.DOUBLE, "fromX", ColumnType.DOUBLE, "fromY", ColumnType.DOUBLE,
				"toX", ColumnType.DOUBLE, "toY", ColumnType.DOUBLE, PERSON_ID, ColumnType.TEXT, "arrivalTime", ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

//		filter for persons, which used the new drt service only
		TextColumn personColumn = persons.textColumn(PERSON);
		persons = persons.where(personColumn.isIn(drtLegs.textColumn(PERSON_ID)));

		//		read base persons and filter them
		Table basePersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(basePersonsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(basePersonsPath)).build());

		TextColumn basePersonColumn = basePersons.textColumn(PERSON);
		basePersons = basePersons.where(basePersonColumn.isIn(drtLegs.textColumn(PERSON_ID)));

		ptLineAnalysis.writeComparisonTable(persons, basePersons, SCORE, PERSON, DRT_PREFIX);

//		print csv file with home coords of drt agents
		ptLineAnalysis.writeHomeLocations(persons, DRT_PREFIX);

//		write income distr of drt agents
		ptLineAnalysis.writeIncomeDistr(persons, incomeLabels, null, DRT_PREFIX);

//		write age distr of drt agents
		ptLineAnalysis.writeAgeDistr(persons, null, DRT_PREFIX);

		for (int i = 0; i < basePersons.columnCount(); i++) {
			Column column = basePersons.column(i);
			if (!column.name().equals(PERSON)) {
				column.setName(column.name() + BASE_SUFFIX);
			}
		}
		Table basePersonsIncomeGroup = basePersons.joinOn(PERSON).inner(persons).retainColumns(PERSON, INCOME_GROUP, SCORE + BASE_SUFFIX);

//		calc mean score for every income group in base and policy and save to table
		Table scoresPerIncomeGroup = persons.summarize(SCORE, mean).by(INCOME_GROUP)
			.joinOn(INCOME_GROUP).inner(basePersonsIncomeGroup.summarize(SCORE + BASE_SUFFIX, mean).by(INCOME_GROUP));

//		write scores per income group
		ptLineAnalysis.writeScorePerIncomeGroupDistr(scoresPerIncomeGroup, incomeLabels, DRT_PREFIX);

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Table baseTrips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(baseTripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(baseTripsPath)).build());

//		get shp of drt service area
		ShpOptions drtServiceArea = null;
		Config config = ConfigUtils.loadConfig(configPath);
		for (DrtConfigGroup drtCfg : ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class).getModalElements()) {
			if (drtCfg.getMode().equals(TransportMode.drt)) {
				drtServiceArea = new ShpOptions(Path.of(
					(drtCfg.drtServiceAreaShapeFile.startsWith("file:/")) ? drtCfg.drtServiceAreaShapeFile.substring(6) : drtCfg.drtServiceAreaShapeFile),
					null, null);
				break;
			}
		}

		IntList drtServiceAreaTripIds = new IntArrayList();
		Geometry geometry = drtServiceArea.getGeometry();

//		filter for trips which start or end in service area
		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);

			Coord startCoord = new Coord(row.getDouble("start_x"), row.getDouble("start_y"));
			Coord endCoord = new Coord(row.getDouble("end_x"), row.getDouble("end_y"));

			if (MGC.coord2Point(startCoord).within(geometry) || MGC.coord2Point(endCoord).within(geometry)) {
				drtServiceAreaTripIds.add(i);
			}
		}

		Table drtServiceAreaTrips = trips.where(Selection.with(drtServiceAreaTripIds.toIntArray()));

//		calc and write mode shares
		calcAndWriteModalShares(drtServiceAreaTrips);

//		aggregate and write OD-relations for drt service sub-area(s)
		aggregateAndWriteDrtODRelations(drtLegs, drtServiceArea);


//		filter for trips with drt only
		TextColumn personTripsColumn = trips.textColumn(PERSON);
		trips = trips.where(personTripsColumn.isIn(drtLegs.textColumn(PERSON_ID)));

		IntList idx = new IntArrayList();

		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);

			Double tripStart = ptLineAnalysis.parseTimeManually(row.getString("dep_time"));
//			waiting time already included in travel time
			Double travelTime = ptLineAnalysis.parseTimeManually(row.getString(TRAV_TIME));

			Table filtered = drtLegs.where(drtLegs.textColumn(PERSON_ID).containsString(row.getString(PERSON)));

			for (int j = 0; j < filtered.rowCount(); j++) {
				Row filteredRow = filtered.row(j);
				if (Range.of(tripStart, tripStart + travelTime).contains(filteredRow.getDouble(DEPARTURE_TIME))) {
					idx.add(i);
					break;
				}
			}
		}
		trips = trips.where(Selection.with(idx.toIntArray()));

//		filter trips of base case for comparison
		StringColumn tripIdColumn = trips.stringColumn(TRIP_ID);
		StringColumn baseTripIdColumn = baseTrips.stringColumn(TRIP_ID);

		baseTrips = baseTrips.where(baseTripIdColumn.isIn(tripIdColumn));

//		the number of trips in both filtered tables should be the same
		if (baseTrips.rowCount() != trips.rowCount()) {
			log.fatal("Number of trips in filtered base case trips table ({}) and pt policy case trips table ({}) is not equal!" +
				" Analysis cannot be continued.", baseTrips.rowCount(), trips.rowCount());
			return 2;
		}

//		calc and write mean stats for policy and base case
		ptLineAnalysis.calcAndWriteMeanStats(trips, persons, baseTrips, basePersons, TransportMode.drt);

//		write tables for comparison of travel time and distance
		ptLineAnalysis.writeComparisonTable(trips, baseTrips, TRAV_TIME, TRIP_ID, DRT_PREFIX);
		ptLineAnalysis.writeComparisonTable(trips, baseTrips, TRAV_DIST, TRIP_ID, DRT_PREFIX);

//		write mode shares to csv
		ptLineAnalysis.writeBaseModeShares(baseTrips, DRT_PREFIX);
		return 0;
	}

	private void aggregateAndWriteDrtODRelations(Table drtLegs, ShpOptions drtServiceArea) {
		StringColumn originZoneId = StringColumn.create(ORIG_ZONE_ID, new String[drtLegs.rowCount()]);
		StringColumn destinationZoneId = StringColumn.create(DEST_ZONE_ID, new String[drtLegs.rowCount()]);

//		add from and to zone id to drt legs
		for (int i = 0; i < drtLegs.rowCount(); i++) {
			Row row = drtLegs.row(i);

			Coord from = new Coord(row.getDouble("fromX"), row.getDouble("fromY"));
			Coord to = new Coord(row.getDouble("toX"), row.getDouble("toY"));

			double origSurface = 0.;
			double destSurface = 0.;
			for (SimpleFeature feature : drtServiceArea.readFeatures()) {
				if (origSurface == 0.) {
					if (MGC.coord2Point(from).within((Geometry) feature.getDefaultGeometry())) {
						String id = feature.getAttribute("id").toString();
						originZoneId.set(i, id);
						origSurface = ((Geometry) feature.getDefaultGeometry()).getArea();
					}
				} else {
//					approximation: if sqm of area is smaller than the matching area before:
//					this is probably the more accurate zone as the bigger ones typically enclose the smaller ones completely
					double area = ((Geometry) feature.getDefaultGeometry()).getArea();

					if (MGC.coord2Point(from).within((Geometry) feature.getDefaultGeometry()) &&
						area < origSurface) {
//						overwrite originZoneId
						String id = feature.getAttribute("id").toString();
						originZoneId.set(i, id);
						origSurface = ((Geometry) feature.getDefaultGeometry()).getArea();
					}
				}

				if (destSurface == 0.) {
					if (MGC.coord2Point(to).within((Geometry) feature.getDefaultGeometry())) {
						String id = feature.getAttribute("id").toString();
						destinationZoneId.set(i, id);
						destSurface = ((Geometry) feature.getDefaultGeometry()).getArea();
					}
				} else {
//					approximation: if sqm of area is smaller than the matching area before:
//					this is probably the more accurate zone as the bigger ones typically enclose the smaller ones completely
					if (MGC.coord2Point(to).within((Geometry) feature.getDefaultGeometry()) &&
						((Geometry) feature.getDefaultGeometry()).getArea() < destSurface) {
//						overwrite destinationZoneId
						String id = feature.getAttribute("id").toString();
						destinationZoneId.set(i, id);
						destSurface = ((Geometry) feature.getDefaultGeometry()).getArea();
					}
				}
			}
		}
		drtLegs.addColumns(originZoneId, destinationZoneId);

//		extract hours from departure time
		DoubleColumn departureTimes = drtLegs.doubleColumn(DEPARTURE_TIME);
		int[] hours = new int[departureTimes.size()];

		for (int i = 0; i < departureTimes.size(); i++) {
			int hour = (int) Math.ceil(departureTimes.get(i) / 3600);
//			handle hour values >=24h
			if (hour >= 24) {
				hour = 23;
			}
			hours[i] = hour;
		}

		IntColumn hourCol = IntColumn.create(DEPARTURE_H, hours);
		drtLegs.addColumns(hourCol);

		Table grouped = drtLegs
			.summarize(DEPARTURE_H, count)
			.by(drtLegs.stringColumn(ORIG_ZONE_ID), drtLegs.stringColumn(DEST_ZONE_ID), hourCol);

// Get all unique origins and destinations
		StringColumn origins = drtLegs.stringColumn(ORIG_ZONE_ID).unique();
		StringColumn destinations = drtLegs.stringColumn(DEST_ZONE_ID).unique();

// Create full range of hours 0–23
		IntColumn departureHour = IntColumn.create(DEPARTURE_H);
		for (int h = 0; h < 24; h++) {
			departureHour.append(h);
		}

// Create a table of all combinations
		List<String> originList = origins.asList();
		List<String> destList = destinations.asList();

		StringColumn fullOrigin = StringColumn.create(ORIG_ZONE_ID);
		StringColumn fullDest = StringColumn.create(DEST_ZONE_ID);
		IntColumn fullHour = IntColumn.create(DEPARTURE_H);

		for (String o : originList) {
			for (String d : destList) {
				for (int h = 0; h < 24; h++) {
					fullOrigin.append(o);
					fullDest.append(d);
					fullHour.append(h);
				}
			}
		}

		Table fullCombinations = Table.create("fullCombinations", fullOrigin, fullDest, fullHour);

// Left join summarized table with all combinations
		Table completeSummary = fullCombinations.joinOn(ORIG_ZONE_ID, DEST_ZONE_ID, DEPARTURE_H)
			.leftOuter(grouped);

// Replace missing counts with 0
		DoubleColumn countCol = completeSummary.doubleColumn("Count [departureHour]");

		for (int i = 0; i < completeSummary.rowCount(); i++) {
//			.get(i) returns null if value NaN
			if (countCol.get(i) == null) {
//				set 0 if NaN/null
				countCol.set(i, 0);
			}
		}

		//Get distinct hour values to use as new column names
		IntColumn hourColumn = completeSummary.intColumn(DEPARTURE_H);
		List<Integer> uniqueHours = hourColumn.unique().asList();

//Create a new empty table for the pivot result
		StringColumn newOrigin = StringColumn.create(ORIG_ZONE_ID);
		StringColumn newDest = StringColumn.create(DEST_ZONE_ID);
		StringColumn origDest = StringColumn.create("origDest");

//Create columns for each hour
		Map<Integer, IntColumn> hourColumns = new HashMap<>();
		for (Integer hour : uniqueHours) {
			hourColumns.put(hour, IntColumn.create(hour.toString()));
		}

//Aggregate values into new rows
		Table aggregatedDrtServiceAreas = Table.create("aggregatedDrtServiceAreas");
		aggregatedDrtServiceAreas.addColumns(origDest, newOrigin, newDest);
		for (IntColumn col : hourColumns.values()) {
			aggregatedDrtServiceAreas.addColumns(col);
		}

		Set<String> seenKeys = new HashSet<>();
		for (Row row : completeSummary) {
			String origin = row.getString(ORIG_ZONE_ID);
			String dest = row.getString(DEST_ZONE_ID);
			int hour = row.getInt(DEPARTURE_H);
			int count = (int) row.getDouble("Count [departureHour]");

			String key = origin + "|" + dest;

			int rowIndex;
			if (!seenKeys.contains(key)) {
				newOrigin.append(origin);
				newDest.append(dest);
				origDest.append(key);

				for (Integer h : uniqueHours) {
					hourColumns.get(h).append(h.equals(hour) ? count : 0);
				}
				seenKeys.add(key);
			} else {
				// Find the index of this row in the pivot table
				rowIndex = aggregatedDrtServiceAreas.stringColumn("origDest").indexOf(key);
				IntColumn col = hourColumns.get(hour);
				col.set(rowIndex, count);
			}
		}
		aggregatedDrtServiceAreas.removeColumns(origDest);
		aggregatedDrtServiceAreas.write().csv(output.getPath("drt_legs_zones_od.csv").toFile());
	}

	private void calcAndWriteModalShares(Table drtServiceAreaTrips) {
//		write all trips in drt service area to csv
		drtServiceAreaTrips.write().csv(output.getPath("trips_in_drt_service_area.csv.gz").toFile());

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

		aggr.write().csv(output.getPath("mode_share.csv").toFile());

		// Norm each dist_group to 1
		for (String label : labels) {
			DoubleColumn distGroupShare = aggr.doubleColumn(SHARE);
			Selection sel = aggr.stringColumn(DIST_GROUP).isEqualTo(label);

			double total = distGroupShare.where(sel).sum();
			if (total > 0)
				distGroupShare.set(sel, distGroupShare.divide(total));
		}
		aggr.write().csv(output.getPath("mode_share_per_dist.csv").toFile());
	}

	private static String cut(double dist, List<Double> distGroups, List<String> labels) {

		int idx = Collections.binarySearch(distGroups, dist);

		if (idx >= 0)
			return labels.get(idx);

		int ins = -(idx + 1);
		return labels.get(ins - 1);
	}
}
