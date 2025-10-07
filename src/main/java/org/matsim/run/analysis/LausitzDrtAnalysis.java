package org.matsim.run.analysis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.lang3.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.jetbrains.annotations.NotNull;
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
import org.matsim.core.utils.gis.GeoFileWriter;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.run.DrtAndIntermodalityOptions;
import picocli.CommandLine;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvReadOptions;
import tech.tablesaw.selection.Selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.run.analysis.PtLineAnalysis.*;
import static tech.tablesaw.aggregate.AggregateFunctions.*;

@CommandLine.Command(name = "drt", description = "Analyze and compare agents who use new drt service from " +
	" policy case and the respective trips in the base case..")
@CommandSpec(requireRunDirectory = true,
	produces = {"drt_persons.csv", "drt_persons_home_locations.csv", "drt_persons_income_groups.csv", "drt_persons_age_groups.csv",
		"mean_travel_stats.csv", "drt_persons_trav_time.csv", "drt_persons_traveled_distance.csv", "drt_persons_base_modal_share.csv",
		"drt_persons_mean_score_per_income_group.csv", "drt_persons_executed_score.csv", "all_persons_income_groups.csv", "all_persons_age_groups.csv",
		"trips_in_drt_service_area.csv.gz", "mode_share.csv", "mode_share_per_dist.csv", "drt_legs_zones_od.csv", "serviceArea.shp", "serviceArea1.dbf",
		"all_persons_aggregated_stats.csv", "drt_persons_aggregated_stats.csv", "all_trips_aggregated_stats.csv", "drt_trips_aggregated_stats.csv",
		"relevant_trips_processed.csv.gz", "relevant_base_trips_processed.csv.gz", "relevant_drt_trips_processed.csv.gz", "relevant_base_trips_of_drt_trips_processed.csv.gz",
		"persons_processed.csv.gz"
	}
)

public class LausitzDrtAnalysis implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(LausitzDrtAnalysis.class);

	@CommandLine.Mixin
	private final InputOptions input = InputOptions.ofCommand(LausitzDrtAnalysis.class);
	@CommandLine.Mixin
	private OutputOptions output = OutputOptions.ofCommand(LausitzDrtAnalysis.class);
	@CommandLine.Option(names = "--income-groups", split = ",", description = "List of income for binning", defaultValue = "0,500,900,1500,2000,3000,4000,5000,6000,7000")
	private List<Integer> incomeGroups;
	@CommandLine.Option(names = "--age-groups", split = ",", description = "List of age for binning", defaultValue = "0,18,30,50,70")
	private List<Integer> ageGroups;
	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;
	@CommandLine.Option(names = "--dist-groups", split = ",", description = "List of distances for binning", defaultValue = "0,1000,2000,5000,10000,20000")
	private List<Double> distGroups;

	private static final String INCOME_GROUP = "incomeGroup";
	static final String PERSON = "person";
	private static final String SHARE = "share";
	private static final String SCORE = "executed_score";
	private static final String INCOME = "income";
	static final String TRAV_TIME = "trav_time";
	static final String TRAV_DIST = "traveled_distance";
	private static final String EUCL_DIST = "euclidean_distance";
	static final String MAIN_MODE = "main_mode";
	static final String TRIP_ID = "trip_id";
	private static final String BASE_SUFFIX = "_base";
	private static final String DIST_GROUP = "dist_group";
	private static final String DEPARTURE_H = "departureHour";
	private static final String DEPARTURE_TIME = "departureTime";
	private static final String PERSON_ID = "personId";
	private static final String DRT_PREFIX = "drt_persons_";
	private static final String ORIG_ZONE_ID = "originZoneId";
	private static final String DEST_ZONE_ID = "destinationZoneId";

	public static void main(String[] args) {
		new LausitzDrtAnalysis().execute(args);
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

		Table fullPersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(personsPath)).build());

//		########################################################### person specific analysis ##################################################################

		Map<String, Range<Integer>> incomeLabels = ptLineAnalysis.getLabels(incomeGroups);
		incomeLabels.put(incomeGroups.getLast() + "+", Range.of(incomeGroups.getLast(), 9999999));
		incomeGroups.add(Integer.MAX_VALUE);

//		filter for real agents only, no freight agents!
		Table freightPersons = fullPersons.where(fullPersons.textColumn(PERSON).containsString("commercialPersonTraffic")
			.or(fullPersons.textColumn(PERSON).containsString("freight"))
			.or(fullPersons.textColumn(PERSON).containsString("goodsTraffic")));
		fullPersons = fullPersons.where(fullPersons.textColumn(PERSON).isNotIn(freightPersons.textColumn(PERSON)));

		//		add income group column to persons table for further analysis
		fullPersons = ptLineAnalysis.addIncomeGroupColumnToTable(fullPersons, incomeLabels);

		//		get general marg ut of money + beta performing from cfg
		Config config = ConfigUtils.loadConfig(configPath);
		double generalBetaMoney = config.scoring().getMarginalUtilityOfMoney();
		double betaPerforming = config.scoring().getPerforming_utils_hr();

//		calc meanIncome for calculation of person specific beta money and further analysis
		DoubleColumn incomeColumn = fullPersons.doubleColumn(INCOME);
		double meanIncome = incomeColumn.mean();

//		write general income and age distr
		ptLineAnalysis.writeIncomeDistr(fullPersons, incomeLabels, "all_persons_income_groups.csv", null);
		ptLineAnalysis.writeAgeDistr(fullPersons, "all_persons_age_groups.csv", null);

		//		read base persons
		Table basePersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(basePersonsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(basePersonsPath)).build());

		Table basePersonsWithoutFreight = basePersons.where(basePersons.textColumn(PERSON).isIn(fullPersons.textColumn(PERSON)));

		//		the number of persons in both filtered person tables should be the same
		if (basePersonsWithoutFreight.rowCount() != fullPersons.rowCount()) {
			log.fatal("Number of persons in base case persons table without freight trips ({}) and drt policy case persons table without freight trips ({}) is not equal! " +
				"Analysis cannot be continued.", basePersonsWithoutFreight.rowCount(), fullPersons.rowCount());
			return 2;
		}

		//		add person specific marg ut of money column and score diff column
		fullPersons = ptLineAnalysis.addPersonSpecificMarginalUtilityOfMoneyColumnAndScoreDiffColumnToTable(fullPersons, basePersonsWithoutFreight, generalBetaMoney,
			meanIncome, betaPerforming);

		fullPersons.write().csv(output.getPath("persons_processed.csv.gz").toFile());

		//		calc and write sum of scores, mean score etc. for all agents to csv
		ptLineAnalysis.calcAndWritePersonAggregatedStats(fullPersons, basePersonsWithoutFreight,"all_persons_", meanIncome);

		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, EUCL_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING));

		Table drtLegs = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(drtLegsPath))
			.columnTypesPartial(Map.of(DEPARTURE_TIME, ColumnType.DOUBLE, "fromX", ColumnType.DOUBLE, "fromY", ColumnType.DOUBLE,
				"toX", ColumnType.DOUBLE, "toY", ColumnType.DOUBLE, PERSON_ID, ColumnType.TEXT, "arrivalTime", ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

//		filter for persons, which used the new drt service only
		TextColumn personColumn = fullPersons.textColumn(PERSON);
		Table persons = fullPersons.where(personColumn.isIn(drtLegs.textColumn(PERSON_ID)));

//		filter base persons for drt users in policy case
		TextColumn basePersonColumn = basePersons.textColumn(PERSON);
		basePersons = basePersons.where(basePersonColumn.isIn(drtLegs.textColumn(PERSON_ID)));

		//		calc meanIncome for pt line users
		DoubleColumn incomeDrtUsersColumn = persons.doubleColumn(INCOME);
		double meanIncomeDrtUsers = incomeDrtUsersColumn.mean();

		//		calc and write sum of scores, mean score etc. for drt users to csv
		ptLineAnalysis.calcAndWritePersonAggregatedStats(persons, basePersons, DRT_PREFIX, meanIncomeDrtUsers);

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

//		########################################################### trip specific analysis ##################################################################

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Table baseTrips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(baseTripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(baseTripsPath)).build());

		Table freightTrips = trips.where(trips.stringColumn(TRIP_ID).containsString("commercialPersonTraffic")
			.or(trips.stringColumn(TRIP_ID).containsString("freight"))
			.or(trips.stringColumn(TRIP_ID).containsString("goodsTraffic")));

		Table tripsWithoutFreight = trips.where(trips.stringColumn(TRIP_ID).isNotIn(freightTrips.stringColumn(TRIP_ID)));

		Map<String, Table> withoutFreightTables = filterBaseTrips(tripsWithoutFreight, baseTrips);
		tripsWithoutFreight = withoutFreightTables.get("policy");
		Table baseTripsWithoutFreight = withoutFreightTables.get("base");
		//		the number of trips in both filtered tables should be the same
		if (baseTripsWithoutFreight.rowCount() != tripsWithoutFreight.rowCount()) {
			log.fatal("Number of trips in base case trips table without freight ({}) and pt policy case trips table without freight ({}) is not equal!" +
				" Analysis cannot be continued.", baseTripsWithoutFreight.rowCount(), tripsWithoutFreight.rowCount());
			return 2;
		}

//		add stats to trips: velocity, monetary cost, (dis)utility and diff of the former to base case
		Map<String, Table> addedStatsTables = ptLineAnalysis.addTripBasedStats(tripsWithoutFreight, baseTripsWithoutFreight);
		tripsWithoutFreight = addedStatsTables.get(POLICY);
		baseTripsWithoutFreight = addedStatsTables.get(BASE);

//		write trips tables with added information to csv
		tripsWithoutFreight.write().csv(output.getPath("relevant_trips_processed.csv.gz").toFile());
		baseTripsWithoutFreight.write().csv(output.getPath("relevant_base_trips_processed.csv.gz").toFile());

//		calc and write sums and diffs of tt for all trips to csv
		ptLineAnalysis.calcAndWriteTripAggregatedStats(tripsWithoutFreight, baseTripsWithoutFreight, "all_trips_");

		ShpOptions drtServiceArea = getAndWriteDrtServiceArea(config);

		List<String> drtServiceAreaTripIds = new ArrayList<>();
		Geometry geometry = drtServiceArea.getGeometry();

//		filter for trips which start or end in service area
		for (int i = 0; i < tripsWithoutFreight.rowCount(); i++) {
			Row row = tripsWithoutFreight.row(i);

			Coord startCoord = new Coord(row.getDouble("start_x"), row.getDouble("start_y"));
			Coord endCoord = new Coord(row.getDouble("end_x"), row.getDouble("end_y"));

			if (MGC.coord2Point(startCoord).within(geometry) || MGC.coord2Point(endCoord).within(geometry)) {
				drtServiceAreaTripIds.add(row.getText(TRIP_ID));
			}
		}

		Table intermediateTrips = tripsWithoutFreight.where(tripsWithoutFreight.textColumn(PERSON).isIn(fullPersons.textColumn(PERSON)));
		Table drtServiceAreaTrips = intermediateTrips.where(intermediateTrips.stringColumn(TRIP_ID).isIn(drtServiceAreaTripIds));

//		calc and write mode shares
		calcAndWriteModalShares(drtServiceAreaTrips);

//		aggregate and write OD-relations for drt service sub-area(s)
		aggregateAndWriteDrtODRelations(drtLegs, drtServiceArea, output, "drt_legs_zones_od.csv",
			"fromX", "fromY", "toX", "toY", DEPARTURE_TIME);

//		filter for trips with drt only
		Table tripsDrt = filterTripsWithDrt(tripsWithoutFreight, drtLegs, ptLineAnalysis);

//		filter trips of base case for comparison
//		apparently we cannot filter like this: baseTrips = baseTrips.where(baseTripIdColumn.isIn(tripIdColumn));
//		in the case of agents stucking, it causes a crash of the whole analysis.
//		rather use filterBaseTrips() and exclude the person from analysis
		Map<String, Table> tripTables = filterBaseTrips(tripsDrt, baseTripsWithoutFreight);
		tripsDrt = tripTables.get(POLICY);
		Table baseTripsDrt = tripTables.get(BASE);

//		the number of trips in both filtered tables should be the same
		if (baseTripsDrt.rowCount() != tripsDrt.rowCount()) {
			log.fatal("Number of trips in filtered base case trips table ({}) and drt policy case trips table ({}) is not equal!" +
				" Analysis cannot be continued.", baseTripsDrt.rowCount(), tripsDrt.rowCount());
			return 2;
		}

		//		write trips tables with added information to csv
		tripsDrt.write().csv(output.getPath("relevant_drt_trips_processed.csv.gz").toFile());
		baseTripsDrt.write().csv(output.getPath("relevant_base_trips_of_drt_trips_processed.csv.gz").toFile());

//		calc and write sums and diffs of tt for drt users to csv
		ptLineAnalysis.calcAndWriteTripAggregatedStats(tripsDrt, baseTripsDrt, "drt_trips_");

//		calc and write mean stats for policy and base case
		ptLineAnalysis.calcAndWriteMeanStats(tripsDrt, persons, baseTripsDrt, basePersons, TransportMode.drt);

//		write tables for comparison of travel time and distance
		ptLineAnalysis.writeComparisonTable(tripsDrt, baseTripsDrt, TRAV_TIME, TRIP_ID, DRT_PREFIX);
		ptLineAnalysis.writeComparisonTable(tripsDrt, baseTripsDrt, TRAV_DIST, TRIP_ID, DRT_PREFIX);

		Table baseTripsOfTrueDrtTrips = filterForBaseTripsOfTrueDrtTrips(tripsDrt, baseTripsDrt);

//		write mode shares to csv
		ptLineAnalysis.writeBaseModeShares(baseTripsOfTrueDrtTrips, DRT_PREFIX);
		return 0;
	}

	private @NotNull ShpOptions getAndWriteDrtServiceArea(Config config) throws IOException {
		//		get shp of drt service area
		ShpOptions drtServiceArea = null;
		for (DrtConfigGroup drtCfg : ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class).getModalElements()) {
			if (drtCfg.getMode().equals(TransportMode.drt)) {
				drtServiceArea = new ShpOptions(Path.of(new DrtAndIntermodalityOptions().getDrtServiceAreaShpPathFromConfig(config)), null, null);
				break;
			}
		}

//		write service area to shp
		GeoFileWriter.writeGeometries(drtServiceArea.readFeatures(), output.getPath("serviceArea.shp").toString());
//		shp and dbf have the same file name and OutputOptions does not allow us to use an option twice, so we have to do this workaround by copying the dbf file
		Files.copy(Path.of(output.getPath("serviceArea.shp").toString().replace(".shp", ".dbf")),
			output.getPath("serviceArea1.dbf"), StandardCopyOption.REPLACE_EXISTING);
		return drtServiceArea;
	}

	private Table filterForBaseTripsOfTrueDrtTrips(Table trips, Table baseTrips) {
		IntList idx = new IntArrayList();

		StringColumn mainModeColumn = trips.stringColumn(MAIN_MODE);

		for (int i = 0; i < trips.rowCount(); i++) {
			String mainMode = mainModeColumn.get(i);

			if (mainMode.equals(TransportMode.drt)) {
				idx.add(i);
			}
		}

		trips = trips.where(Selection.with(idx.toIntArray()));

		StringColumn tripIdColumn = trips.stringColumn(TRIP_ID);
		StringColumn baseTripIdColumn = baseTrips.stringColumn(TRIP_ID);
		baseTrips = baseTrips.where(baseTripIdColumn.isIn(tripIdColumn));

		//		the number of trips in both filtered tables should be the same
		if (baseTrips.rowCount() != trips.rowCount()) {
			log.fatal("Number of trips in filtered base case trips table ({}) and drt policy case trips table ({}) is not equal!" +
				" Analysis cannot be continued.", baseTrips.rowCount(), trips.rowCount());
			throw new IllegalStateException();
		}
		return baseTrips;
	}

	private Map<String, Table> filterBaseTrips(Table trips, Table baseTrips) {
		IntList idx = new IntArrayList();

		StringColumn tripIdColumn = trips.stringColumn(TRIP_ID);
		StringColumn baseTripIdColumn = baseTrips.stringColumn(TRIP_ID);

		log.info("start filtering base case trips table for trip ids in policy case trips table.");

		for (int i = 0; i < trips.rowCount(); i++) {
			String id = tripIdColumn.get(i);

			if (baseTrips.where(baseTripIdColumn.isEqualTo(id)).rowCount() == 0) {
				log.info("Trip with id {} is present in policy trips table, but not in base trips table. Most probably the agent stucks in the base case. " +
					"Trip {} will be ignored for this analysis.", id, id);
			} else if (baseTrips.where(baseTripIdColumn.isEqualTo(id)).rowCount() > 1) {
				log.fatal("There are {} trips with id {} in the base case. Duplicate ids should never exist! Aborting.", baseTrips.where(baseTripIdColumn.isEqualTo(id)).rowCount(), id);
			} else {
				idx.add(i);
			}
		}
		trips = trips.where(Selection.with(idx.toIntArray()));
		log.info("finished filtering base case trips table for trip ids in policy case trips table.");
		return Map.of("policy", trips, "base", baseTrips.where(baseTripIdColumn.isIn(tripIdColumn)));
	}

	private Table filterTripsWithDrt(Table trips, Table drtLegs, PtLineAnalysis ptLineAnalysis) {
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
		return trips.where(Selection.with(idx.toIntArray()));
	}

	void aggregateAndWriteDrtODRelations(Table tripsOrLegs, ShpOptions drtServiceArea, OutputOptions outputOpt, String outFileName, String fromColNameX, String fromColNameY, String toColNameX, String toColNameY, String depTimeColName) {
		tripsOrLegs = addOriginAndDestinationZoneIds(tripsOrLegs, drtServiceArea, fromColNameX, fromColNameY, toColNameX, toColNameY);

//		extract hours from departure time
		DoubleColumn departureTimes = tripsOrLegs.doubleColumn(depTimeColName);
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
		tripsOrLegs.addColumns(hourCol);

		Table grouped = tripsOrLegs
			.summarize(DEPARTURE_H, count)
			.by(tripsOrLegs.stringColumn(ORIG_ZONE_ID), tripsOrLegs.stringColumn(DEST_ZONE_ID), hourCol);

// Get all unique origins and destinations
		StringColumn origins = tripsOrLegs.stringColumn(ORIG_ZONE_ID).unique();
		StringColumn destinations = tripsOrLegs.stringColumn(DEST_ZONE_ID).unique();

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
		Table aggregatedAreas = Table.create("aggregatedAreas");
		aggregatedAreas.addColumns(origDest, newOrigin, newDest);
		for (IntColumn col : hourColumns.values()) {
			aggregatedAreas.addColumns(col);
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
				rowIndex = aggregatedAreas.stringColumn("origDest").indexOf(key);
				IntColumn col = hourColumns.get(hour);
				col.set(rowIndex, count);
			}
		}
		aggregatedAreas.removeColumns(origDest);
		aggregatedAreas.write().csv(outputOpt.getPath(outFileName).toFile());
	}

	private static Table addOriginAndDestinationZoneIds(Table legsOrTrips, ShpOptions drtServiceArea, String fromColNameX, String fromColNameY, String toColNameX, String toColNameY) {
		StringColumn originZoneId = StringColumn.create(ORIG_ZONE_ID, new String[legsOrTrips.rowCount()]);
		StringColumn destinationZoneId = StringColumn.create(DEST_ZONE_ID, new String[legsOrTrips.rowCount()]);

//		add from and to zone id to drt legs
		for (int i = 0; i < legsOrTrips.rowCount(); i++) {
			Row row = legsOrTrips.row(i);

			Coord from = new Coord(row.getDouble(fromColNameX), row.getDouble(fromColNameY));
			Coord to = new Coord(row.getDouble(toColNameX), row.getDouble(toColNameY));

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
		legsOrTrips.addColumns(originZoneId, destinationZoneId);
		return legsOrTrips;
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
