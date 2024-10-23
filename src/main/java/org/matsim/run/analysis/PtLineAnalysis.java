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
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.PopulationUtils;
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

@CommandLine.Command(name = "pt-line", description = "Get all agents who use the newly created pt line.")
@CommandSpec(requireRunDirectory = true,
	produces = {"pt_persons.csv", "pt_persons_home_locations.csv", "pt_persons_income_groups.csv", "pt_persons_age_groups.csv",
		"mean_travel_stats.csv", "pt_persons_trav_time.csv", "pt_persons_traveled_distance.csv", "pt_persons_base_modal_share.csv"
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
	@CommandLine.Option(names = "--age-groups", split = ",", description = "List of income for binning", defaultValue = "0,18,30,50,70")
	private List<Integer> ageGroups;
	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;

	private final Map<String, List<Double>> ptPersons = new HashMap<>();

	private final String incomeGroup = "incomeGroup";
	private final String person = "person";
	private final String share = "share";
	private final String ageGroup = "ageGroup";

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
		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(output.getPath("pt_persons.csv")), getCsvFormat())) {
			printer.printRecord("person", "time");
			for (Map.Entry<String, List<Double>> e : ptPersons.entrySet()) {
				for (Double time : e.getValue()) {
					printer.printRecord(e.getKey(), time);
				}
			}
		}

//		all necessary file input paths are defined here
		String personsPath = globFile(input.getRunDirectory(), "*output_persons.csv.gz").toString();
		String tripsPath = globFile(input.getRunDirectory(), "*output_trips.csv.gz").toString();
		String basePersonsPath = globFile(basePath, "*output_persons.csv.gz").toString();
		String baseTripsPath = globFile(basePath, "*output_trips.csv.gz").toString();

		Table persons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personsPath))
			.columnTypesPartial(Map.of("person", ColumnType.TEXT, "executed_score", ColumnType.LONG))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(personsPath)).build());

//		TODO: anpassen auf verwendete columns / columntypes
		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of("person", ColumnType.TEXT,
			"trav_time", ColumnType.STRING, "wait_time", ColumnType.STRING, "dep_time", ColumnType.STRING,
			"longest_distance_mode", ColumnType.STRING, "main_mode", ColumnType.STRING,
			"start_activity_type", ColumnType.TEXT, "end_activity_type", ColumnType.TEXT,
			"first_pt_boarding_stop", ColumnType.TEXT, "last_pt_egress_stop", ColumnType.TEXT));

		// Map.of only has 10 argument max
		columnTypes.put("traveled_distance", ColumnType.LONG);
		columnTypes.put("euclidean_distance", ColumnType.LONG);

//		filter for persons, which used the new pt line in pt policy case
		TextColumn personColumn = persons.textColumn("person");
		persons = persons.where(personColumn.isIn(ptPersons.keySet()));

		//		read base persons and filter them
		Table basePersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(basePersonsPath))
			.columnTypesPartial(Map.of("person", ColumnType.TEXT, "executed_score", ColumnType.LONG))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(basePersonsPath)).build());

		TextColumn basePersonColumn = basePersons.textColumn("person");
		basePersons = basePersons.where(basePersonColumn.isIn(ptPersons.keySet()));

//		print csv file with home coords of new pt line agents
		writeHomeLocations(persons);

//		write income distr of new pt line agents
		writeIncomeDistr(persons);

//		write age distr of new pt line agents
		writeAgeDistr(persons);

		Table trips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(tripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(tripsPath)).build());

		Table baseTrips = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(baseTripsPath))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(CsvOptions.detectDelimiter(baseTripsPath)).build());

//		filter for trips with new pt line only
		TextColumn personTripsColumn = persons.textColumn("person");
		trips = trips.where(personTripsColumn.isIn(ptPersons.keySet()));

		IntList idx = new IntArrayList();

		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);

			Double tripStart = (double) LocalTime.parse(row.getString("dep_time")).toSecondOfDay();
			Double travelTime = (double) LocalTime.parse(row.getString("trav_time")).toSecondOfDay();

			List<Double> enterTimes = ptPersons.get(row.getString("person"));

			for (Double enterTime : enterTimes) {
				if (Range.of(tripStart, tripStart + travelTime).contains(enterTime)) {
					idx.add(i);
				}
			}
		}
		trips = trips.where(Selection.with(idx.toIntArray()));

//		filter trips of base case for comparison
		TextColumn tripIdColumn = trips.textColumn("trip_number");
		TextColumn baseTripIdColumn = baseTrips.textColumn("trip_number");

		baseTrips = baseTrips.where(baseTripIdColumn.isIn(tripIdColumn));

//		the number of trips in both filtered tables should be the same
		if (baseTrips.rowCount() != trips.rowCount()) {
			log.fatal("Number of trips in filtered base case trips table ({}) and pt policy case trips table ({}) is not equal!" +
				" Analysis cannot be continued.", baseTrips.rowCount(), trips.rowCount());
			return 2;
		}

		double meanTravelTimePolicy = calcMean(trips.column("trav_time"));
		double meanTravelDistancePolicy = calcMean(trips.column("traveled_distance"));
		double meanEuclideanDistancePolicy = calcMean(trips.column("euclidean_distance"));
		double meanTravelTimeBase = calcMean(baseTrips.column("trav_time"));
		double meanTravelDistanceBase = calcMean(baseTrips.column("traveled_distance"));
		double meanEuclideanDistanceBase = calcMean(baseTrips.column("euclidean_distance"));

		if (meanTravelTimePolicy <= 0 || meanTravelTimeBase <= 0) {
			log.fatal("Mean travel time for either base ({}) or policy case ({}) are zero. Mean travel velocity cannot" +
				"be calculated! Divison by 0 not possible!", meanTravelTimeBase, meanTravelTimePolicy);
			return 2;
		}

		double meanVelocityPolicy = meanTravelDistancePolicy / meanTravelTimePolicy;
		double meanVelocityBase = meanTravelDistanceBase / meanTravelTimeBase;

//		write mean stats to csv
		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("mean_travel_stats.csv").toString()), getCsvFormat())) {
			printer.printRecord("\"mean travel time policy case\"", f.format(meanTravelTimePolicy));
			printer.printRecord("\"mean travel time base case\"", f.format(meanTravelTimeBase));
			printer.printRecord("\"mean travel distance policy case\"", f.format(meanTravelDistancePolicy));
			printer.printRecord("\"mean travel distance base case\"", f.format(meanTravelDistanceBase));
			printer.printRecord("\"mean trip velocity policy case\"", f.format(meanVelocityPolicy));
			printer.printRecord("\"mean trip velocity base case\"", f.format(meanVelocityBase));
			printer.printRecord("\"mean euclidean distance policy case\"", f.format(meanEuclideanDistancePolicy));
			printer.printRecord("\"mean euclidean distance base case\"", f.format(meanEuclideanDistanceBase));
		}

//		write tables for comparison of travel time and distance
		writeComparisonTable(trips, baseTrips, "trav_time");
		writeComparisonTable(trips, baseTrips, "traveled_distance");

//		calc shares for new pt line trips in base case
		StringColumn mainModeColumn = baseTrips.stringColumn("main_mode");

		Table counts = baseTrips.countBy(mainModeColumn);

		counts.addColumns(
			counts.doubleColumn("Count")
				.divide(mainModeColumn.size())
				.setName("share")
		);

		//		TODO: further analysis see trello
//		score vergleich. scores sind in persons.csv enthalten
//		mean score berechnen
//		mean score per income group?
//		tabelle mit score base <-> policy

//		write mode shares to csv
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("pt_persons_base_modal_share.csv").toString()), getCsvFormat())) {
			printer.printRecord("main_mode", "share");
			for (int i = 0; i < counts.rowCount(); i++) {
				Row row = counts.row(i);
				printer.printRecord(row.getString("main_mode"), row.getDouble("share"));
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}






		return 0;
	}

	private void writeComparisonTable(Table policy, Table base, String paramName) {
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("pt_persons_" + paramName + ".csv").toString()), getCsvFormat())) {
			printer.printRecord("trip", paramName + "_policy", paramName + "_base");
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
				printer.printRecord(row.getText("trip_id"), policyValue, baseValue);
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
//				distances are saved as doubles
				value = doubleColumn.get(i);
			}
			total += value;
		}
		return total / column.size();
	}

	private void writeHomeLocations(Table persons) throws IOException {
		//		TODO: think about adding first act coords here or even act before / after pt trip
		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(output.getPath("pt_persons_home_locations.csv")), getCsvFormat())) {
			printer.printRecord("personId", "home_x", "home_y");

			for (int i = 0; i < persons.rowCount(); i++) {
				Row row = persons.row(i);
				printer.printRecord(row.getText("person"), row.getDouble("home_x"), row.getDouble("home_y"));
			}
		}
	}

	private void writeIncomeDistr(Table persons) {
		Map<String, Range<Integer>> labels = getLabels(incomeGroups);
		labels.put(incomeGroups.getLast() + "+", Range.of(incomeGroups.getLast(), 9999999));
		incomeGroups.add(Integer.MAX_VALUE);

		persons.addColumns(StringColumn.create(incomeGroup));

		for (int i = 0; i < persons.rowCount() - 1; i++) {
			Row row = persons.row(i);

			int income = (int) Math.round(Double.parseDouble(row.getString("income")));
			String p = row.getText("person");

			if (income < 0) {
				log.error("income {} of person {} is negative. This should not happen!", income, p);
				throw new IllegalArgumentException();
			}

			for (Map.Entry<String, Range<Integer>> e : labels.entrySet()) {
				Range<Integer> range = e.getValue();
				if (range.contains(income)) {
					row.setString(incomeGroup, e.getKey());
					break;
				}
			}
		}

		List<String> incomeDistr = getDistr(persons, incomeGroup, labels);

//		print income distr
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("pt_persons_income_groups.csv").toString()), getCsvFormat())) {
			printer.printRecord("incomeGroup", "Count [person]", share);
			for (String s : incomeDistr) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private void writeAgeDistr(Table persons) {
		Map<String, Range<Integer>> labels = getLabels(ageGroups);
		labels.put(ageGroups.getLast() + "+", Range.of(ageGroups.getLast(), 120));
		ageGroups.add(Integer.MAX_VALUE);

		persons.addColumns(StringColumn.create(ageGroup));

		for (int i = 0; i < persons.rowCount() - 1; i++) {
			Row row = persons.row(i);

			int age = (int) Math.round(Double.parseDouble(row.getString("income")));
			String p = row.getText("person");

			if (age < 0) {
				log.error("age {} of person {} is negative. This should not happen!", age, p);
				throw new IllegalArgumentException();
			}

			for (Map.Entry<String, Range<Integer>> e : labels.entrySet()) {
				Range<Integer> range = e.getValue();
				if (range.contains(age)) {
					row.setString(ageGroup, e.getKey());
					break;
				}
			}
		}

		List<String> ageDistr = getDistr(persons, ageGroup, labels);

//		print age distr
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("pt_persons_age_groups.csv").toString()), getCsvFormat())) {
			printer.printRecord("ageGroup", "Count [person]", share);
			for (String s : ageDistr) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
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
		Table aggr = persons.summarize(person, count).by(group);

//		how to sort rows here? agg.sortOn does not work! Using workaround instead. -sme0324
		DoubleColumn shareCol = aggr.numberColumn(1).divide(aggr.numberColumn(1).sum()).setName(share);
		aggr.addColumns(shareCol);

		List<String> distr = new ArrayList<>();

		for (String k : labels.keySet()) {
			for (int i = 0; i < aggr.rowCount() - 1; i++) {
				Row row = aggr.row(i);
				if (row.getString(group).equals(k)) {
					distr.add(k + "," + row.getDouble("Count [person]") + "," + row.getDouble("share"));
					break;
				}
			}
		}

		distr.sort(Comparator.comparingInt(PtLineAnalysis::getLowerBound));
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


//	private record TripData(
//		double baseTravelTime,
//		double policyTravelTime,
//		double baseTravelDistance,
//		double policyTravelDistance,
//		double baseTravelDistanceEuclidean,
//		double policyTravelDistanceEuclidean,
//		double baseTravelSpeed,
//		double policyTravelSpeed
//	) {}
}
