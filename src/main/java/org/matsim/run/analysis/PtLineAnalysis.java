package org.matsim.run.analysis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
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

import static org.matsim.application.ApplicationUtils.globFile;
import static tech.tablesaw.aggregate.AggregateFunctions.*;

@CommandLine.Command(name = "pt-line", description = "Analyze and compare agents who use new pt connection from " +
	" policy case and the respective trips in the base case..")
@CommandSpec(requireRunDirectory = true,
	produces = {"pt_persons.csv", "pt_persons_home_locations.csv", "pt_persons_income_groups.csv", "pt_persons_age_groups.csv",
		"mean_travel_stats.csv", "pt_persons_trav_time.csv", "pt_persons_traveled_distance.csv", "pt_persons_base_modal_share.csv",
		"pt_persons_mean_score_per_income_group.csv", "pt_persons_executed_score.csv", "all_persons_income_groups.csv", "all_persons_age_groups.csv"
	}
)

public class PtLineAnalysis implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(PtLineAnalysis.class);

	@CommandLine.Mixin
	private final InputOptions input = InputOptions.ofCommand(PtLineAnalysis.class);
	@CommandLine.Mixin
	private OutputOptions output = OutputOptions.ofCommand(PtLineAnalysis.class);
	@CommandLine.Option(names = "--income-groups", split = ",", description = "List of income for binning", defaultValue = "0,500,900,1500,2000,3000,4000,5000,6000,7000")
	private List<Integer> incomeGroups;
	@CommandLine.Option(names = "--age-groups", split = ",", description = "List of age for binning", defaultValue = "0,18,30,50,70")
	private List<Integer> ageGroups;
	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;

	private final Map<String, List<Double>> ptPersons = new HashMap<>();

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

	PtLineAnalysis(List<Integer> incomeGroups, List<Integer> ageGroups, OutputOptions output) {
		this.incomeGroups = incomeGroups;
		this.ageGroups = ageGroups;
		this.output = output;
	}

	private PtLineAnalysis() {
	}

	public static void main(String[] args) {
		new PtLineAnalysis().execute(args);
	}


	@Override
	public Integer call() throws Exception {
		String eventsFile = globFile(input.getRunDirectory(), "*output_events.xml.gz").toString();

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new NewPtLineEventHandler());
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsFile);
		manager.finishProcessing();

//		write persons, who use new pt line and their entry time to csv file
		writePtPersons();

//		all necessary file input paths are defined here
		String personsPath = globFile(input.getRunDirectory(), "*output_persons.csv.gz").toString();
		String tripsPath = globFile(input.getRunDirectory(), "*output_trips.csv.gz").toString();
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
		writeIncomeDistr(persons, incomeLabels, "all_persons_income_groups.csv", null);
		writeAgeDistr(persons, "all_persons_age_groups.csv", null);



		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, "dep_time", ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, EUCL_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING));

//		filter for persons, which used the new pt line in pt policy case
		TextColumn personColumn = persons.textColumn(PERSON);
		persons = persons.where(personColumn.isIn(ptPersons.keySet()));

		//		read base persons and filter them
		Table basePersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(basePersonsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(basePersonsPath)).build());

		TextColumn basePersonColumn = basePersons.textColumn(PERSON);
		basePersons = basePersons.where(basePersonColumn.isIn(ptPersons.keySet()));

		writeComparisonTable(persons, basePersons, SCORE, PERSON, "pt_persons_");

//		print csv file with home coords of new pt line agents
		writeHomeLocations(persons, "pt_persons_");

//		write income distr of new pt line agents
		writeIncomeDistr(persons, incomeLabels, null, "pt_persons_");

//		write age distr of new pt line agents
		writeAgeDistr(persons, null, "pt_persons_");

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
		writeScorePerIncomeGroupDistr(scoresPerIncomeGroup, incomeLabels, "pt_persons_");

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Table baseTrips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(baseTripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(baseTripsPath)).build());

//		filter for trips with new pt line only
		TextColumn personTripsColumn = trips.textColumn(PERSON);
		trips = trips.where(personTripsColumn.isIn(ptPersons.keySet()));

		IntList idx = new IntArrayList();

		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);

			Double tripStart = parseTimeManually(row.getString("dep_time"));
//			waiting time already included in travel time
			Double travelTime = parseTimeManually(row.getString(TRAV_TIME));

			List<Double> enterTimes = ptPersons.get(row.getString(PERSON));

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

//		calc and write mean stats for policy and base case
		calcAndWriteMeanStats(trips, persons, baseTrips, basePersons, "pt line");

//		write tables for comparison of travel time and distance
		writeComparisonTable(trips, baseTrips, TRAV_TIME, TRIP_ID, "pt_persons_");
		writeComparisonTable(trips, baseTrips, TRAV_DIST, TRIP_ID, "pt_persons_");

//		write mode shares to csv
		writeBaseModeShares(baseTrips, "pt_persons_");
		return 0;
	}

	void calcAndWriteMeanStats(Table trips, Table persons, Table baseTrips, Table basePersons, String policy) throws IOException {
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
			printer.printRecord("\"" + policy + " users (10pct)\"", f.format(persons.rowCount()));
			printer.printRecord("\"" + policy + " trips (10pct)\"", f.format(trips.rowCount()));
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

	void writeBaseModeShares(Table baseTrips, String prefix) {
		//		calc shares for new pt line trips in base case
		StringColumn mainModeColumn = baseTrips.stringColumn(MAIN_MODE);

		Table counts = baseTrips.countBy(mainModeColumn);

		counts.addColumns(
			counts.intColumn("Count")
				.divide(mainModeColumn.size())
				.setName(SHARE)
		);

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(prefix + "base_modal_share.csv").toString()), getCsvFormat())) {
			printer.printRecord(MAIN_MODE, SHARE);
			for (int i = 0; i < counts.rowCount(); i++) {
				Row row = counts.row(i);
				printer.printRecord(row.getString(MAIN_MODE), row.getDouble(SHARE));
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private void writePtPersons() throws IOException {
		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(output.getPath("pt_persons.csv")), getCsvFormat())) {
			printer.printRecord(PERSON, "time");
			for (Map.Entry<String, List<Double>> e : ptPersons.entrySet()) {
				for (Double time : e.getValue()) {
					printer.printRecord(e.getKey(), time);
				}
			}
		}
	}

	void writeScorePerIncomeGroupDistr(Table scoresPerIncomeGroup, Map<String, Range<Integer>> labels, String prefix) {

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(prefix + "mean_score_per_income_group.csv").toString()), getCsvFormat())) {
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

			distr.sort(Comparator.comparingInt(PtLineAnalysis::getLowerBound));

			for (String s : distr) {
				printer.printRecord(s);
			}

		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	void writeComparisonTable(Table policy, Table base, String paramName, String id, String prefix) {
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(prefix + paramName + ".csv").toString()), getCsvFormat())) {
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

	void writeHomeLocations(Table persons, String prefix) throws IOException {
		//		y think about adding first act coords here or even act before / after pt trip
		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(output.getPath(prefix + "home_locations.csv")), getCsvFormat())) {
			printer.printRecord(PERSON, "home_x", "home_y");

			for (int i = 0; i < persons.rowCount(); i++) {
				Row row = persons.row(i);
				printer.printRecord(row.getText(PERSON), row.getDouble("home_x"), row.getDouble("home_y"));
			}
		}
	}

	void writeIncomeDistr(Table persons, Map<String, Range<Integer>> labels, String outputString, String prefix) {
		List<String> incomeDistr = getDistr(persons, INCOME_GROUP, labels);

		String file = (outputString != null) ? outputString : prefix + "income_groups.csv";

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

	void writeAgeDistr(Table persons, String outputString, String prefix) {
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

		String file = (outputString != null) ? outputString : prefix + "age_groups.csv";


//		print age distr
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(file).toString()), getCsvFormat())) {
			printer.printRecord(AGE_GROUP, COUNT_PERSON, SHARE);
			for (String s : ageDistr) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
//		remove max integer from age groups because method is used twice and thus list would contain value twice
//		we need a List, because we need to use the .getLast() methods above
		ageGroups.remove(ageGroups.getLast());
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

	Table addIncomeGroupColumnToTable(Table persons, Map<String, Range<Integer>> incomeLabels) {
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

	Map<String, Range<Integer>> getLabels(List<Integer> groups) {
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

		distr.sort(Comparator.comparingInt(PtLineAnalysis::getLowerBound));
		return distr;
	}

	static CSVFormat getCsvFormat() {
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

	double parseTimeManually(String time) {
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


	private final class NewPtLineEventHandler implements PersonEntersVehicleEventHandler {

		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			if (event.getVehicleId().toString().contains("RE-VSP1") && !event.getPersonId().toString().contains("pt_")) {
				if (!ptPersons.containsKey(event.getPersonId().toString())) {
					ptPersons.put(event.getPersonId().toString(), new ArrayList<>());
				}
				ptPersons.get(event.getPersonId().toString()).add(event.getTime());
			}
		}
	}
}
