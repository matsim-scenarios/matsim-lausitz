package org.matsim.run.analysis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvReadOptions;
import tech.tablesaw.selection.Selection;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.application.ApplicationUtils.globFile;
import static tech.tablesaw.aggregate.AggregateFunctions.count;
import static tech.tablesaw.aggregate.AggregateFunctions.mean;

@CommandLine.Command(name = "drt", description = "Analyze and compare agents who use new drt service from " +
	" policy case and the respective trips in the base case..")
@CommandSpec(requireRunDirectory = true,
	produces = {"drt_persons.csv", "drt_persons_home_locations.csv", "drt_persons_income_groups.csv", "drt_persons_age_groups.csv",
		"mean_travel_stats.csv", "drt_persons_trav_time.csv", "drt_persons_traveled_distance.csv", "drt_persons_base_modal_share.csv",
		"drt_persons_mean_score_per_income_group.csv", "drt_persons_executed_score.csv", "all_persons_income_groups.csv", "all_persons_age_groups.csv",
		"trips_in_drt_service_area.csv.gz", "mode_share.csv", "mode_share_per_dist.csv"
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
	private List<Long> distGroups;

	private final Map<String, List<Double>> drtPersons = new HashMap<>();

	private static final String INCOME_GROUP = "incomeGroup";
	private static final String PERSON = "person";
	private static final String SHARE = "share";
	private static final String AGE_GROUP = "ageGroup";
	private static final String SCORE = "executed_score";
	private static final String INCOME = "income";
	private static final String TRAV_TIME = "trav_time";
	private static final String TRAV_DIST = "traveled_distance";
	private static final String EUCL_DIST = "euclidean_distance";
	private static final String MAIN_MODE = "main_mode";
	private static final String TRIP_ID = "trip_id";
	private static final String BASE_SUFFIX = "_base";
	private static final String COUNT_PERSON = "Count [person]";
	private static final String DIST_GROUP = "dist_group";

	public static void main(String[] args) {
		new DrtAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		String eventsFile = globFile(input.getRunDirectory(), "*output_events.xml.gz").toString();

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new DrtPersonEventHandler());
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsFile);
		manager.finishProcessing();

//		write persons, who use new drt service and their entry time to csv file
		writeDrtPersons();

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

		Map<String, Range<Integer>> incomeLabels = getLabels(incomeGroups);
		incomeLabels.put(incomeGroups.getLast() + "+", Range.of(incomeGroups.getLast(), 9999999));
		incomeGroups.add(Integer.MAX_VALUE);

//		filter for real agents only, no freight agents!
		Table freightPersons = persons.where(persons.textColumn(PERSON).containsString("commercialPersonTraffic")
			.or(persons.textColumn(PERSON).containsString("freight"))
			.or(persons.textColumn(PERSON).containsString("goodsTraffic")));
		persons = persons.where(persons.textColumn(PERSON).isNotIn(freightPersons.textColumn(PERSON)));

		//		add income group column to persons table for further analysis
		persons = addIncomeGroupColumnToTable(persons, incomeLabels);

//		write general income and age distr
		writeIncomeDistr(persons, incomeLabels, "all_persons_income_groups.csv");
		writeAgeDistr(persons, "all_persons_age_groups.csv");



		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, EUCL_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING));

//		filter for persons, which used the new drt service only
		TextColumn personColumn = persons.textColumn(PERSON);
		persons = persons.where(personColumn.isIn(drtPersons.keySet()));

		//		read base persons and filter them
		Table basePersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(basePersonsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(basePersonsPath)).build());

		TextColumn basePersonColumn = basePersons.textColumn(PERSON);
		basePersons = basePersons.where(basePersonColumn.isIn(drtPersons.keySet()));

		writeComparisonTable(persons, basePersons, SCORE, PERSON);

//		print csv file with home coords of drt agents
		writeHomeLocations(persons);

//		write income distr of drt agents
		writeIncomeDistr(persons, incomeLabels, null);

//		write age distr of drt agents
		writeAgeDistr(persons, null);

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
		writeScorePerIncomeGroupDistr(scoresPerIncomeGroup, incomeLabels);

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Table baseTrips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(baseTripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(baseTripsPath)).build());

//		get shp of drt service area
		Geometry geometry = null;
		Config config = ConfigUtils.loadConfig(configPath);
		for (DrtConfigGroup drtCfg : ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class).getModalElements()) {
			if (drtCfg.getMode().equals(TransportMode.drt)) {
				geometry = new ShpOptions(Path.of(drtCfg.drtServiceAreaShapeFile), null, null).getGeometry();
				break;
			}
		}

		IntList drtServiceAreaTripIds = new IntArrayList();

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

//		TODO: analysis on origin and destination drt zones/bubbles for each trip
//		maybe we have to use legs here?
//		tag each trip/leg with an origin and destination drt zone
//		result should be a csv with columns origin;destination and maybe hours 1-24

		Table drtLegs = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(drtLegsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());



//		filter for trips with drt only
		TextColumn personTripsColumn = trips.textColumn(PERSON);
		trips = trips.where(personTripsColumn.isIn(drtPersons.keySet()));

		IntList idx = new IntArrayList();

		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);

			Double tripStart = parseTimeManually(row.getString("dep_time"));
//			waiting time already included in travel time
			Double travelTime = parseTimeManually(row.getString(TRAV_TIME));

			List<Double> enterTimes = drtPersons.get(row.getString(PERSON));

			for (Double enterTime : enterTimes) {
				if (Range.of(tripStart, tripStart + travelTime).contains(enterTime)) {
					idx.add(i);
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

//		TODO: look into and adapt the following methods

//		calc and write mean stats for policy and base case
		calcAndWriteMeanStats(trips, persons, baseTrips, basePersons);

//		write tables for comparison of travel time and distance
		writeComparisonTable(trips, baseTrips, TRAV_TIME, TRIP_ID);
		writeComparisonTable(trips, baseTrips, TRAV_DIST, TRIP_ID);

//		write mode shares to csv
		writeBaseModeShares(baseTrips);
		return 0;
	}

	private void calcAndWriteModalShares(Table drtServiceAreaTrips) {
//		write all trips in drt service area to csv
		drtServiceAreaTrips.write().csv(output.getPath("trips_in_drt_service_area.csv.gz").toFile());

		List<String> labels = new ArrayList<>();
		for (int i = 0; i < distGroups.size() - 1; i++) {
			labels.add(String.format("%d - %d", distGroups.get(i), distGroups.get(i + 1)));
		}
		labels.add(distGroups.getLast() + "+");
		distGroups.add(Long.MAX_VALUE);

		StringColumn distGroup = drtServiceAreaTrips.longColumn(TRAV_DIST)
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

	private static String cut(long dist, List<Long> distGroups, List<String> labels) {

		int idx = Collections.binarySearch(distGroups, dist);

		if (idx >= 0)
			return labels.get(idx);

		int ins = -(idx + 1);
		return labels.get(ins - 1);
	}

	private void calcAndWriteMeanStats(Table trips, Table persons, Table baseTrips, Table basePersons) throws IOException {
		double meanTravelTimePolicy = calcMean(trips.column(TRAV_TIME));
		double meanTravelDistancePolicy = calcMean(trips.column(TRAV_DIST));
		double meanEuclideanDistancePolicy = calcMean(trips.column(EUCL_DIST));
		double meanScorePolicy = calcMean(persons.column(SCORE));
		double meanTravelTimeBase = calcMean(baseTrips.column(TRAV_TIME));
		double meanTravelDistanceBase = calcMean(baseTrips.column(TRAV_DIST));
		double meanEuclideanDistanceBase = calcMean(baseTrips.column(EUCL_DIST));
		double meanScoreBase = calcMean(basePersons.column(SCORE + BASE_SUFFIX));

		if (meanTravelTimePolicy <= 0 || meanTravelTimeBase <= 0) {
			log.fatal("Mean travel time for either base ({}) or policy case ({}) are zero. Mean travel velocity cannot" +
				"be calculated! Divison by 0 not possible!", meanTravelTimeBase, meanTravelTimePolicy);
			throw new IllegalArgumentException();
		}

		double meanVelocityPolicy = meanTravelDistancePolicy / meanTravelTimePolicy;
		double meanVelocityBase = meanTravelDistanceBase / meanTravelTimeBase;

//		write mean stats to csv
		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("mean_travel_stats.csv").toString()), getCsvFormat())) {
			printer.printRecord("\"pt line users (10pct)\"", f.format(persons.rowCount()));
			printer.printRecord("\"pt line trips (10pct)\"", f.format(trips.rowCount()));
			printer.printRecord("\"mean travel time policy case [s]\"", f.format(meanTravelTimePolicy));
			printer.printRecord("\"mean travel time base case [s]\"", f.format(meanTravelTimeBase));
			printer.printRecord("\"mean travel distance policy case [m]\"", f.format(meanTravelDistancePolicy));
			printer.printRecord("\"mean travel distance base case [m]\"", f.format(meanTravelDistanceBase));
			printer.printRecord("\"mean trip velocity policy case [m/s]\"", f.format(meanVelocityPolicy));
			printer.printRecord("\"mean trip velocity base case [m/s]\"", f.format(meanVelocityBase));
			printer.printRecord("\"mean euclidean distance policy case [m]\"", f.format(meanEuclideanDistancePolicy));
			printer.printRecord("\"mean euclidean distance base case [m]\"", f.format(meanEuclideanDistanceBase));
			printer.printRecord("\"mean score policy case [util]\"", f.format(meanScorePolicy));
			printer.printRecord("\"mean score base case [util]\"", f.format(meanScoreBase));
		}
	}

	private void writeBaseModeShares(Table baseTrips) {
		//		calc shares for new pt line trips in base case
		StringColumn mainModeColumn = baseTrips.stringColumn(MAIN_MODE);

		Table counts = baseTrips.countBy(mainModeColumn);

		counts.addColumns(
			counts.intColumn("Count")
				.divide(mainModeColumn.size())
				.setName(SHARE)
		);

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("drt_persons_base_modal_share.csv").toString()), getCsvFormat())) {
			printer.printRecord(MAIN_MODE, SHARE);
			for (int i = 0; i < counts.rowCount(); i++) {
				Row row = counts.row(i);
				printer.printRecord(row.getString(MAIN_MODE), row.getDouble(SHARE));
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private void writeDrtPersons() throws IOException {
		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(output.getPath("drt_persons.csv")), getCsvFormat())) {
			printer.printRecord(PERSON, "time");
			for (Map.Entry<String, List<Double>> e : drtPersons.entrySet()) {
				for (Double time : e.getValue()) {
					printer.printRecord(e.getKey(), time);
				}
			}
		}
	}

	private void writeScorePerIncomeGroupDistr(Table scoresPerIncomeGroup, Map<String, Range<Integer>> labels) {

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("drt_persons_mean_score_per_income_group.csv").toString()), getCsvFormat())) {
			printer.printRecord(INCOME_GROUP, "mean_score_base", "mean_score_policy");

			List<String> distr = new ArrayList<>();

			for (String k : labels.keySet()) {
				boolean labelFound = false;
				for (int i = 0; i < scoresPerIncomeGroup.rowCount(); i++) {
					Row row = scoresPerIncomeGroup.row(i);
					if (row.getString(INCOME_GROUP).equals(k)) {
						distr.add(k + "," + row.getDouble(2) + "," + row.getDouble(1));
						labelFound = true;
						break;
					}
				}
				if (!labelFound) {
					distr.add(k + "," + 0 + "," + 0);
				}
			}

			distr.sort(Comparator.comparingInt(DrtAnalysis::getLowerBound));

			for (String s : distr) {
				printer.printRecord(s);
			}

		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private void writeComparisonTable(Table policy, Table base, String paramName, String id) {
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("drt_persons_" + paramName + ".csv").toString()), getCsvFormat())) {
			printer.printRecord(id, paramName + "_policy", paramName + BASE_SUFFIX);
			for (int i = 0; i < policy.rowCount(); i++) {
				Row row = policy.row(i);
				Row baseRow = base.row(i);

				String policyValue = null;
				String baseValue = null;

				if (policy.column(paramName) instanceof StringColumn) {
					policyValue = row.getString(paramName);
					baseValue = baseRow.getString(paramName);
				} else if (policy.column(paramName) instanceof DoubleColumn) {
					policyValue = String.valueOf(row.getDouble(paramName));
					baseValue = String.valueOf(baseRow.getDouble(paramName));
				}
				printer.printRecord(row.getText(id), policyValue, baseValue);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private void writeHomeLocations(Table persons) throws IOException {
		//		y think about adding first act coords here or even act before / after pt trip
		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(output.getPath("drt_persons_home_locations.csv")), getCsvFormat())) {
			printer.printRecord(PERSON, "home_x", "home_y");

			for (int i = 0; i < persons.rowCount(); i++) {
				Row row = persons.row(i);
				printer.printRecord(row.getText(PERSON), row.getDouble("home_x"), row.getDouble("home_y"));
			}
		}
	}

	private void writeIncomeDistr(Table persons, Map<String, Range<Integer>> labels, String outputString) {
		List<String> incomeDistr = getDistr(persons, INCOME_GROUP, labels);

		String file = (outputString != null) ? outputString : "drt_persons_income_groups.csv";

//		print income distr
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(file).toString()), getCsvFormat())) {
			printer.printRecord(INCOME_GROUP, COUNT_PERSON, SHARE);
			for (String s : incomeDistr) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private void writeAgeDistr(Table persons, String outputString) {
		Map<String, Range<Integer>> labels = getLabels(ageGroups);
		labels.put(ageGroups.getLast() + "+", Range.of(ageGroups.getLast(), 120));
		ageGroups.add(Integer.MAX_VALUE);

//		only add ageGroup column if not present
		Optional.of(AGE_GROUP)
			.filter(col -> !persons.columnNames().contains(col))
			.ifPresent(col -> persons.addColumns(StringColumn.create(col)));

		for (int i = 0; i < persons.rowCount(); i++) {
			Row row = persons.row(i);

			int age = row.getInt("age");
			String p = row.getText(PERSON);

			if (age < 0) {
				log.error("age {} of person {} is negative. This should not happen!", age, p);
				throw new IllegalArgumentException();
			}

			for (Map.Entry<String, Range<Integer>> e : labels.entrySet()) {
				Range<Integer> range = e.getValue();
				if (range.contains(age)) {
					row.setString(AGE_GROUP, e.getKey());
					break;
				}
			}
		}

		List<String> ageDistr = getDistr(persons, AGE_GROUP, labels);

		String file = (outputString != null) ? outputString : "drt_persons_age_groups.csv";


//		print age distr
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(file).toString()), getCsvFormat())) {
			printer.printRecord(AGE_GROUP, COUNT_PERSON, SHARE);
			for (String s : ageDistr) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private Double calcMean(Column column) {
		double total = 0;

		for (int i = 0; i < column.size(); i++) {
			double value = 0;
			if (column instanceof StringColumn stringColumn) {
//				travel time is saved in hh:mm:ss format, thus read as string
				value = LocalTime.parse(stringColumn.get(i)).toSecondOfDay();
			} else if (column instanceof DoubleColumn doubleColumn) {
//				distances / scores are saved as doubles
				value = doubleColumn.get(i);
			}
			total += value;
		}
		return total / column.size();
	}

	private Table addIncomeGroupColumnToTable(Table persons, Map<String, Range<Integer>> incomeLabels) {
		persons.addColumns(StringColumn.create(INCOME_GROUP));

		for (int i = 0; i < persons.rowCount(); i++) {
			Row row = persons.row(i);

			int income = (int) Math.round(row.getDouble(INCOME));
			String p = row.getText(PERSON);

			if (income < 0) {
				log.error("income {} of person {} is negative. This should not happen!", income, p);
				throw new IllegalArgumentException();
			}

			for (Map.Entry<String, Range<Integer>> e : incomeLabels.entrySet()) {
				Range<Integer> range = e.getValue();
				if (range.contains(income)) {
					row.setString(INCOME_GROUP, e.getKey());
					break;
				}
			}
		}
		return persons;
	}

	private Map<String, Range<Integer>> getLabels(List<Integer> groups) {
		Map<String, Range<Integer>> labels = new HashMap<>();
		for (int i = 0; i < groups.size() - 1; i++) {
			labels.put(String.format("%d - %d", groups.get(i), groups.get(i + 1) - 1),
				Range.of(groups.get(i), groups.get(i + 1) - 1));
		}
		return labels;
	}

	private @NotNull List<String> getDistr(Table persons, String group, Map<String, Range<Integer>> labels) {
		Table aggr = persons.summarize(PERSON, count).by(group);

//		how to sort rows here? agg.sortOn does not work! Using workaround instead. -sme0324
		DoubleColumn shareCol = aggr.numberColumn(1).divide(aggr.numberColumn(1).sum()).setName(SHARE);
		aggr.addColumns(shareCol);

		List<String> distr = new ArrayList<>();

		for (String k : labels.keySet()) {
			boolean labelFound = false;
			for (int i = 0; i < aggr.rowCount(); i++) {
				Row row = aggr.row(i);
				if (row.getString(group).equals(k)) {
					distr.add(k + "," + row.getDouble(COUNT_PERSON) + "," + row.getDouble(SHARE));
					labelFound = true;
					break;
				}
			}
			if (!labelFound) {
				distr.add(k + "," + 0 + "," + 0);
			}
		}

		distr.sort(Comparator.comparingInt(DrtAnalysis::getLowerBound));
		return distr;
	}

	private static CSVFormat getCsvFormat() {
		return CSVFormat.DEFAULT.builder()
			.setQuote(null)
			.setDelimiter(',')
			.setRecordSeparator("\r\n")
			.build();
	}

	private static int getLowerBound(String s) {
		String regex = " - ";
		if (s.contains("+")) {
			regex = "\\+";
		}
		return Integer.parseInt(s.split(regex)[0]);
	}

	private double parseTimeManually(String time) {
		String[] parts = time.split(":");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Invalid time format: " + time);
		}

		double hours = Double.parseDouble(parts[0]);
		double minutes = Double.parseDouble(parts[1]);
		double seconds = Double.parseDouble(parts[2]);

		// Validate minutes and seconds
		if (minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
			throw new IllegalArgumentException("Invalid minutes or seconds in: " + time);
		}

		return hours * 3600 + minutes * 60 + seconds;
	}


	private final class DrtPersonEventHandler implements PersonDepartureEventHandler {
		@Override
		public void handleEvent(PersonDepartureEvent event) {
			if (event.getLegMode().equals(TransportMode.drt)) {
				if (!drtPersons.containsKey(event.getPersonId().toString())) {
					drtPersons.put(event.getPersonId().toString(), new ArrayList<>());
				}
				drtPersons.get(event.getPersonId().toString()).add(event.getTime());
			}
		}
	}
}
