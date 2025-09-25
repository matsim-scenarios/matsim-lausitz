package org.matsim.run.analysis;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.run.scenarios.LausitzScenario;
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
		"pt_persons_mean_score_per_income_group.csv", "pt_persons_executed_score.csv", "all_persons_income_groups.csv", "all_persons_age_groups.csv",
		"all_persons_aggregated_stats.csv", "pt_persons_aggregated_stats.csv", "all_trips_aggregated_stats.csv", "pt_trips_aggregated_stats.csv"
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
	private static final String PT_PERSONS_PREFIX = "pt_persons_";
	private static final String INCOME_DEP_BETA_MONEY = "incomeDepBetaMoney";
	private static final String TRAV_TIME_DIFF = "trav_time_diff";
	private static final String TRAV_DIST_DIFF = "traveled_distance_diff";
	private static final String SCORE_DIFF = "executed_score_diff";
	private static final String TRAV_VEL = "trav_velocity";
	private static final String TRAV_VEL_DIFF = "trav_velocity_diff";
	private static final String MON_COST = "monetary_cost";
	private static final String UT_NON_MON = "utility_non_monetary";
	private static final String UT_TOTAL = "utility_total";
	private static final String MON_COST_DIFF = "monetary_cost_diff";
	private static final String UT_NON_MON_DIFF = "utility_non_monetary_diff";
	private static final String UT_TOTAL_DIFF = "utility_total_diff";
	private static final String TRIP_NUMBER = "trip_number";
	private static final String DEP_TIME = "dep_time";
	static final String AMOUNT = "amount";
	static final String PURPOSE = "purpose";
	static final String POLICY = "policy";
	static final String BASE = "base";

	PtLineAnalysis(List<Integer> incomeGroups, List<Integer> ageGroups, OutputOptions output) {
		this.incomeGroups = incomeGroups;
		this.ageGroups = ageGroups;
		this.output = output;
	}

	public PtLineAnalysis() {
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
		String configPath = globFile(input.getRunDirectory(), "*output_config.xml").toString();
		String personMoneyEventsPath = globFile(input.getRunDirectory(), "*output_personMoneyEvents.tsv.gz").toString();

		Table personMoneyEvents = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personMoneyEventsPath))
			.columnTypesPartial(Map.of("time", ColumnType.DOUBLE, PERSON, ColumnType.TEXT, AMOUNT, ColumnType.DOUBLE, PURPOSE, ColumnType.STRING))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(personMoneyEventsPath)).build());

		Table fullPersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(personsPath)).build());

//		########################################################### person specific analysis ##################################################################

		Map<String, Range<Integer>> incomeLabels = getLabels(incomeGroups);
		incomeLabels.put(incomeGroups.getLast() + "+", Range.of(incomeGroups.getLast(), 9999999));
		incomeGroups.add(Integer.MAX_VALUE);

//		filter for person agents only, no freight agents!
		Table freightPersons = fullPersons.where(fullPersons.textColumn(PERSON).containsString("commercialPersonTraffic")
			.or(fullPersons.textColumn(PERSON).containsString("freight"))
			.or(fullPersons.textColumn(PERSON).containsString("goodsTraffic")));
		fullPersons = fullPersons.where(fullPersons.textColumn(PERSON).isNotIn(freightPersons.textColumn(PERSON)));

		//		add income group column to persons table for further analysis
		fullPersons = addIncomeGroupColumnToTable(fullPersons, incomeLabels);

		//		get general marg ut of money from cfg
		Config config = ConfigUtils.loadConfig(configPath);
		double generalBetaMoney = config.scoring().getMarginalUtilityOfMoney();

//		calc meanIncome for calculation of person specific beta money and further analysis
		DoubleColumn incomeColumn = fullPersons.doubleColumn(INCOME);
		double meanIncome = incomeColumn.mean();

//		write general income and age distr
		writeIncomeDistr(fullPersons, incomeLabels, "all_persons_income_groups.csv", null);
		writeAgeDistr(fullPersons, "all_persons_age_groups.csv", null);

		//		read base persons
		Table basePersons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(basePersonsPath))
			.columnTypesPartial(Map.of(PERSON, ColumnType.TEXT, SCORE, ColumnType.DOUBLE, INCOME, ColumnType.DOUBLE))
			.sample(false)
			.separator(CsvOptions.detectDelimiter(basePersonsPath)).build());

		Table basePersonsWithoutFreight = basePersons.where(basePersons.textColumn(PERSON).isIn(fullPersons.textColumn(PERSON)));

		//		the number of persons in both filtered person tables should be the same
		if (basePersonsWithoutFreight.rowCount() != fullPersons.rowCount()) {
			log.fatal("Number of persons in base case persons table without freight trips ({}) and pt policy case persons table without freight trips ({}) is not equal! " +
				"Analysis cannot be continued.", basePersonsWithoutFreight.rowCount(), fullPersons.rowCount());
			return 2;
		}

		//		add person specific marg ut of money column and score diff column
		fullPersons = addPersonSpecificMarginalUtilityOfMoneyColumnAndScoreDiffColumnToTable(fullPersons, basePersonsWithoutFreight, generalBetaMoney, meanIncome);

//		calc and write sum of scores, mean score etc. for all agents to csv
		calcAndWritePersonAggregatedStats(fullPersons, basePersonsWithoutFreight, "all_persons_", meanIncome);

		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(PERSON, ColumnType.TEXT,
			TRAV_TIME, ColumnType.STRING, DEP_TIME, ColumnType.STRING, MAIN_MODE, ColumnType.STRING,
			TRAV_DIST, ColumnType.DOUBLE, EUCL_DIST, ColumnType.DOUBLE, TRIP_ID, ColumnType.STRING, TRIP_NUMBER, ColumnType.INTEGER));

//		filter for persons, which used the new pt line in pt policy case
		TextColumn personColumn = fullPersons.textColumn(PERSON);
		Table persons = fullPersons.where(personColumn.isIn(ptPersons.keySet()));

//		filter for pt line users in base persons
		TextColumn basePersonColumn = basePersons.textColumn(PERSON);
		basePersons = basePersons.where(basePersonColumn.isIn(ptPersons.keySet()));

		//		the number of persons in both filtered person tables should be the same
		if (basePersons.rowCount() != persons.rowCount()) {
			log.fatal("Number of persons in base case persons table for pt line users ({}) and pt policy case persons table for pt line users ({}) is not equal!" +
				"Analysis cannot be continued.", basePersons, persons.rowCount());
			return 2;
		}

//		calc meanIncome for pt line users
		DoubleColumn incomePtLineUsersColumn = persons.doubleColumn(INCOME);
		double meanIncomePtLineUsers = incomePtLineUsersColumn.mean();

//		calc and write sum of scores, mean score etc. for pt line users to csv
		calcAndWritePersonAggregatedStats(persons, basePersons, PT_PERSONS_PREFIX, meanIncomePtLineUsers);

		writeComparisonTable(persons, basePersons, SCORE, PERSON, PT_PERSONS_PREFIX);

//		print csv file with home coords of new pt line agents
		writeHomeLocations(persons, PT_PERSONS_PREFIX);

//		write income distr of new pt line agents
		writeIncomeDistr(persons, incomeLabels, null, PT_PERSONS_PREFIX);

//		write age distr of new pt line agents
		writeAgeDistr(persons, null, PT_PERSONS_PREFIX);

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
		writeScorePerIncomeGroupDistr(scoresPerIncomeGroup, incomeLabels, PT_PERSONS_PREFIX);

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
		Table baseTripsWithoutFreight = baseTrips.where(baseTrips.stringColumn(TRIP_ID).isIn(tripsWithoutFreight.stringColumn(TRIP_ID)));

		//		the number of trips in both filtered tables should be the same
		if (baseTripsWithoutFreight.rowCount() != tripsWithoutFreight.rowCount()) {
			log.fatal("Number of trips in base case trips table without freight ({}) and pt policy case trips table without freight ({}) is not equal!" +
				" Analysis cannot be continued.", baseTripsWithoutFreight.rowCount(), tripsWithoutFreight.rowCount());
			return 2;
		}

		//		DONE: we may need the personmoney events for the pt and drt fare stuff
//		DONE: calc monetary cost of each trip based on mode
//		DONE: calc (dis)utility component (without cost) of each trip based on mode
//		DONE: calc total (dis)utility of each trip
//		DONE: the above for base and policy
//		DONE: calc diff of disutility base and policy
		//		DONE: add for policy and base:trip velocity; for policy: diff to base of tt, distance, velocity
//		Done: calc means of all new columns

//		add stats to trips: velocity, monetary cost, (dis)utility and diff of the former to base case
		Map<String, Table> addedStatsTables = addTripBasedStats(tripsWithoutFreight, baseTripsWithoutFreight,
			config, fullPersons, personMoneyEvents, LausitzScenario.FunctionalityHandling.DISABLED);
		tripsWithoutFreight = addedStatsTables.get(POLICY);
		baseTripsWithoutFreight = addedStatsTables.get(BASE);

//		calc and write sums and diffs of tt for all trips to csv
		calcAndWriteTripAggregatedStats(tripsWithoutFreight, baseTripsWithoutFreight, "all_trips_");

//		filter for trips with new pt line only
		TextColumn personTripsColumn = trips.textColumn(PERSON);
		trips = trips.where(personTripsColumn.isIn(ptPersons.keySet()));

		IntList idx = new IntArrayList();

		for (int i = 0; i < trips.rowCount(); i++) {
			Row row = trips.row(i);

			Double tripStart = parseTimeManually(row.getString(DEP_TIME));
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

		//		calc and write sums and diffs of tt for pt line users to csv
		calcAndWriteTripAggregatedStats(trips, baseTrips, "pt_trips_");

//		calc and write mean stats for policy and base case
		calcAndWriteMeanStats(trips, persons, baseTrips, basePersons, "pt line");

//		write tables for comparison of travel time and distance
		writeComparisonTable(trips, baseTrips, TRAV_TIME, TRIP_ID, PT_PERSONS_PREFIX);
		writeComparisonTable(trips, baseTrips, TRAV_DIST, TRIP_ID, PT_PERSONS_PREFIX);

//		write mode shares to csv
		writeBaseModeShares(baseTrips, PT_PERSONS_PREFIX);
		return 0;
	}

	Map<String, Table> addTripBasedStats(Table trips, Table baseTrips, Config config, Table persons,
										 Table personMoneyEvents, LausitzScenario.FunctionalityHandling drtFareHandling) {

		Map<String, ScoringConfigGroup.ModeParams> modeParams = config.scoring().getModes();
		double betaTransfer = config.scoring().getUtilityOfLineSwitch();

		Map<String, Table> tripsTables = Map.of(POLICY, trips, BASE, baseTrips);

//		calc and add monetary cost, utility to each trip
		for (Table table : tripsTables.values()) {
			table.addColumns(DoubleColumn.create(TRAV_VEL), DoubleColumn.create(MON_COST), DoubleColumn.create(UT_NON_MON), DoubleColumn.create(UT_TOTAL));

			for (int i = 0; i < table.rowCount(); i++) {
				Row row = table.row(i);

				String mode = row.getString(MAIN_MODE);
				String person = row.getText(PERSON);
				String tripId = row.getString(TRIP_ID);
				String departureTime = row.getString(DEP_TIME);
				String travelTime = row.getString(TRAV_TIME);
				double travelDist = row.getDouble(TRAV_DIST);

//				first: add travel velocity to trip
				row.setDouble(TRAV_VEL, travelDist / parseTimeManually(travelTime));

				Table personOfTrip = persons.where(persons.textColumn(PERSON).isEqualTo(person));

				if (personOfTrip.rowCount() != 1) {
					log.fatal("Tried to filter persons table for person {} and found {} matches instead of 1.", person, personOfTrip.rowCount());
					throw new IllegalStateException();
				}

				double personSpecificBetaMoney = personOfTrip.doubleColumn(INCOME_DEP_BETA_MONEY).get(0);

				if (!modeParams.containsKey(mode)) {
					log.fatal("Mode {} was not simulated in this model, this should not happen!", mode);
					throw new IllegalStateException();
				}

//				monetary cost [€/trip] = dailyMonetaryConstant / numberTripsWithMode + monetaryDistanceRateMode * distance + fare + fareRefund/numberPtOrDrtTrips
				ScoringConfigGroup.ModeParams params = modeParams.get(mode);

//				filter trips table 1) for the person and 2) for the mode
				Table tripsOfPerson = table.where(table.textColumn(PERSON).isEqualTo(person));
				Table tripsOfPersonWithMode = tripsOfPerson.where(tripsOfPerson.stringColumn(MAIN_MODE).isEqualTo(mode));

				int numModeTrips = tripsOfPersonWithMode.rowCount();

				double dailyMonetaryCostComponent = params.getDailyMonetaryConstant() / numModeTrips;
				double monetaryDistanceCostComponent = params.getMonetaryDistanceRate() * row.getDouble(TRAV_DIST);
				double fareCostComponent = 0;

				if (mode.equals(TransportMode.pt) || mode.equals(TransportMode.drt)) {
//					for pt/drt fare refunds we also have to take the trips with the other mode into account.
//					if not, we will calculate more refund than actually paid in the following
//					example: person with 3 trips, 1 pt and 2 drt. daily refund of 6€. when calculating cost for pt: 1 pt trip = 6€ refund/trip.
//					when calculating cost for drt: 2 drt trips = 3€ refund / trip = 6€ refund.
//					in total: 6€ pt refund + 6€ drt refund = 12€ refund != 6€ refund which was actually paid.
					numModeTrips = tripsOfPerson.where(tripsOfPerson.stringColumn(MAIN_MODE).isIn(TransportMode.pt, TransportMode.drt)).rowCount();
					log.info(numModeTrips);

//					filter personMoneyEvents for 1) person and 2) current trip
					Table fareEventsOfPerson = personMoneyEvents
						.where(personMoneyEvents.textColumn(PERSON).isEqualTo(person));

					fareEventsOfPerson.write().csv("C:/Users/Simon/Desktop/wd/2025-09-22/testNewDrtAnalysis/policy/fareEvents_person.csv");

					log.info(departureTime);
					log.info(travelTime);
					log.info(tripId);

					log.info(parseTimeManually(departureTime));
					log.info(parseTimeManually(travelTime));
					log.info(parseTimeManually(departureTime) + parseTimeManually(travelTime));

//					TODO: sth is worng with the following syntax, I cant see why, mb too tired.
//					java.lang.IllegalStateException: Column 05:59:08 is not present in table
//

					Table fareForCurrentTrip = fareEventsOfPerson
						.where(fareEventsOfPerson.doubleColumn("time")
							.isBetweenInclusive(parseTimeManually(departureTime), parseTimeManually(row.getString(departureTime)) + parseTimeManually(travelTime)));

//					we need to differ between pt and drt here because drt fare could be switched off
					switch (mode) {
						case TransportMode.pt:
							if (fareForCurrentTrip.rowCount() != 1) {
								log.fatal("When trying to filter for personMoneyEvent for pt trip {} {} events were filtered " +
									"from global personMoneyEvents file, but 1 event is expected.", tripId, fareForCurrentTrip.rowCount());
								throw new IllegalArgumentException();
							}
							fareCostComponent += fareForCurrentTrip.doubleColumn(AMOUNT).get(0);
							break;
						case TransportMode.drt:
							if (drtFareHandling == LausitzScenario.FunctionalityHandling.ENABLED && fareForCurrentTrip.rowCount() == 1) {
								fareCostComponent += fareForCurrentTrip.doubleColumn(AMOUNT).get(0);
							} else if (drtFareHandling == LausitzScenario.FunctionalityHandling.ENABLED && fareForCurrentTrip.rowCount() != 1) {
								log.fatal("When trying to filter for personMoneyEvent for drt/pt with drt trip {} {} events were filtered " +
									"from global personMoneyEvents file, but 1 event is expected.", tripId, fareForCurrentTrip.rowCount());
								throw new IllegalArgumentException();
							}
							break;
						default:
							throw new IllegalStateException();
					}

//					check for potential fare refunds
					Table fareRefundsOfPerson = fareEventsOfPerson
						.where(fareEventsOfPerson.stringColumn(PURPOSE).eval(("refund")::contains));

					if (fareRefundsOfPerson.rowCount() == 1) {
						fareCostComponent += fareRefundsOfPerson.doubleColumn(AMOUNT).get(0) / numModeTrips;
					} else if (fareRefundsOfPerson.rowCount() > 1) {
						log.fatal("Person {} has {} (should only have one) fare refund events. This should not happen.", person, fareRefundsOfPerson.rowCount());
						throw new IllegalArgumentException();
					}
				}
				double monetaryCostTrip = dailyMonetaryCostComponent + monetaryDistanceCostComponent + fareCostComponent;

//				utility [utils/trip] = ASC + margUtTrav * tt + betaTransfer * 0/1
				double transferUtilityComponent = 0;

//				check if there has been a pt transfer
				if (Arrays.stream(row.getString("modes").split("-")).filter(s -> s.equals(TransportMode.pt)).count() > 1) {
					transferUtilityComponent += betaTransfer;
				}
				double utilityNonMonetaryTrip = params.getConstant() + params.getMarginalUtilityOfTraveling() * parseTimeManually(travelTime) / 3600 + transferUtilityComponent;

//				total trip utility [utils/trip] = sum(monetary cost component * personSpecificUtMoney) + utility
				double totalUtilityTrip = dailyMonetaryCostComponent * personSpecificBetaMoney +
					monetaryDistanceCostComponent * personSpecificBetaMoney + fareCostComponent * personSpecificBetaMoney;

				row.setDouble(MON_COST, monetaryCostTrip);
				row.setDouble(UT_NON_MON, utilityNonMonetaryTrip);
				row.setDouble(UT_TOTAL, totalUtilityTrip);
			}
		}

//		add diffs of monetary cost, utilities, travDist, tt, travVel to base case for each policy trip
		for (Map.Entry<String, Table> e : tripsTables.entrySet()) {
			if (e.getKey().equals(POLICY)) {
				Table table = e.getValue();
				table.addColumns(DoubleColumn.create(TRAV_DIST_DIFF), DoubleColumn.create(TRAV_TIME_DIFF), DoubleColumn.create(TRAV_VEL_DIFF),
					DoubleColumn.create(MON_COST_DIFF), DoubleColumn.create(UT_NON_MON_DIFF), DoubleColumn.create(UT_TOTAL_DIFF));

				for (int i = 0; i < table.rowCount(); i++) {
					Row row = table.row(i);

					String tripId = row.getString(TRIP_ID);
					String travelTime = row.getString(TRAV_TIME);
					double travelDist = row.getDouble(TRAV_DIST);
					double travelVel = row.getDouble(TRAV_VEL);
					double monetaryCostTripPolicy = row.getDouble(MON_COST);
					double utilityNonMonetaryTripPolicy = row.getDouble(UT_NON_MON);
					double totalUtilityTripPolicy = row.getDouble(UT_TOTAL);

					Table baseTable = tripsTables.get(BASE);

					baseTable = baseTable.where(baseTable.stringColumn(TRIP_ID).isEqualTo(tripId));

					if (baseTable.rowCount() != 1) {
						log.fatal("When trying to filter for policy trip with id {} in base trips {}  were trips filtered" +
							", but 1 trips is expected.", tripId, baseTable.rowCount());
						throw new IllegalArgumentException();
					}

					String travelTimeBase = baseTable.stringColumn(TRAV_TIME).get(0);
					double travelDistBase = baseTable.doubleColumn(TRAV_DIST).get(0);
					double travelVelBase = baseTable.doubleColumn(TRAV_VEL).get(0);
					double monetaryCostTripBase = baseTable.doubleColumn(MON_COST).get(0);
					double utilityNonMonetaryTripBase = baseTable.doubleColumn(UT_NON_MON).get(0);
					double totalUtilityTripBase = baseTable.doubleColumn(UT_TOTAL).get(0);

					row.setDouble(TRAV_DIST_DIFF, travelDist - travelDistBase);
					row.setDouble(TRAV_TIME_DIFF, parseTimeManually(travelTime) - parseTimeManually(travelTimeBase));
					row.setDouble(TRAV_VEL_DIFF, travelVel - travelVelBase);
					row.setDouble(MON_COST_DIFF, monetaryCostTripPolicy - monetaryCostTripBase);
					row.setDouble(UT_NON_MON_DIFF, utilityNonMonetaryTripPolicy - utilityNonMonetaryTripBase);
					row.setDouble(UT_TOTAL_DIFF, totalUtilityTripPolicy - totalUtilityTripBase);
				}
			}
		}
		return tripsTables;
	}

	Table addPersonSpecificMarginalUtilityOfMoneyColumnAndScoreDiffColumnToTable(Table persons, Table basePersons, double generalBetaMoney, double meanIncome) {
		persons.addColumns(DoubleColumn.create(INCOME_DEP_BETA_MONEY), DoubleColumn.create(SCORE_DIFF));

		for (int i = 0; i < persons.rowCount(); i++) {
			Row row = persons.row(i);

			String person = row.getText(PERSON);
			double scorePolicy = row.getDouble(SCORE);

			Table basePersonsFiltered = basePersons.where(basePersons.textColumn(PERSON).isEqualTo(person));

			if (basePersonsFiltered.rowCount() != 1) {
				log.fatal("When trying to filter for policy person with id {} in base persons {} persons were filtered" +
					", but 1 person is expected.", person, basePersonsFiltered.rowCount());
				throw new IllegalArgumentException();
			}
			double scoreBase = basePersonsFiltered.doubleColumn(SCORE).get(0);

			double income = row.getDouble(INCOME);
			row.setDouble(INCOME_DEP_BETA_MONEY, generalBetaMoney * (meanIncome / income));
			row.setDouble(SCORE_DIFF, scorePolicy - scoreBase);
		}
		return persons;
	}

	void calcAndWriteTripAggregatedStats(Table trips, Table baseTrips, String prefix) throws IOException {
		double sumTravelTime = calcSumOfStringColumn(trips.stringColumn(TRAV_TIME));
		double sumBaseTravelTime = calcSumOfStringColumn(baseTrips.stringColumn(TRAV_TIME));

		//		write stats to csv
		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(prefix + "aggregated_stats.csv").toString()), getCsvFormat())) {
			printer.printRecord("\" policy case sum travel times [s]\"", f.format(sumTravelTime));
			printer.printRecord("\" base case sum travel times [s]\"", f.format(sumBaseTravelTime));
			printer.printRecord("\" diff sum travel times [s]\"", f.format(sumBaseTravelTime - sumTravelTime));
		}
	}

	private double calcSumOfStringColumn(StringColumn stringColumn) {
		double total = 0;

		for (int i = 0; i < stringColumn.size(); i++) {
//			travel time is saved in hh:mm:ss format, thus read as string
			double value = parseTimeManually(stringColumn.get(i));
			total += value;
		}
		return total;
	}

	void calcAndWritePersonAggregatedStats(Table persons, Table basePersons, String prefix, double meanIncome) throws IOException {
		DoubleColumn scoreColumn = persons.doubleColumn(SCORE);
		DoubleColumn baseScoreColumn = basePersons.doubleColumn(SCORE);
		DoubleColumn scoreDiffColumn = persons.doubleColumn(SCORE_DIFF);

		double sumScores = scoreColumn.sum();
		double sumBaseScores = baseScoreColumn.sum();
		double meanScore = scoreColumn.mean();
		double meanBaseScore = baseScoreColumn.mean();
		double meanScoreDiff = scoreDiffColumn.mean();

		double meanIncomeDepUtilityOfMoney = persons.doubleColumn(INCOME_DEP_BETA_MONEY).mean();

		//		write stats to csv
		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath(prefix + "aggregated_stats.csv").toString()), getCsvFormat())) {
			printer.printRecord("\" base case sum scores [util]\"", f.format(sumBaseScores));
			printer.printRecord("\" policy case sum scores [util]\"", f.format(sumScores));
			printer.printRecord("\" diff sum scores [util]\"", f.format(sumScores - sumBaseScores));
			printer.printRecord("\" base case mean score [util]\"", f.format(meanBaseScore));
			printer.printRecord("\" case mean score [util]\"", f.format(meanScore));
			printer.printRecord("\" diff mean score [util]\"", f.format(meanScoreDiff));
			printer.printRecord("\" mean income [€]\"", f.format(meanIncome));
			printer.printRecord("\" mean income dependent utility of money [util/€]\"", f.format(meanIncomeDepUtilityOfMoney));
		}
	}

	void calcAndWriteMeanStats(Table trips, Table persons, Table baseTrips, Table basePersons, String policy) throws IOException {
		double meanTravelTimePolicy = calcMean(trips.column(TRAV_TIME));
		double meanTravelDistancePolicy = calcMean(trips.column(TRAV_DIST));
		double meanVelocityPolicy = calcMean(trips.column(TRAV_VEL));
		double meanEuclideanDistancePolicy = calcMean(trips.column(EUCL_DIST));
		double meanScorePolicy = calcMean(persons.column(SCORE));
		double meanTravelTimeBase = calcMean(baseTrips.column(TRAV_TIME));
		double meanTravelDistanceBase = calcMean(baseTrips.column(TRAV_DIST));
		double meanVelocityBase = calcMean(baseTrips.column(TRAV_VEL));
		double meanEuclideanDistanceBase = calcMean(baseTrips.column(EUCL_DIST));
		double meanScoreBase = calcMean(basePersons.column(SCORE + BASE_SUFFIX));
		double meanTravelTimeDiff = calcMean(trips.column(TRAV_TIME_DIFF));
		double meanTravelDistanceDiff = calcMean(trips.column(TRAV_DIST_DIFF));
		double meanVelocityDiff = calcMean(trips.column(TRAV_VEL_DIFF));
		double meanScoreDiff = calcMean(persons.column(SCORE_DIFF));

		if (meanTravelTimePolicy <= 0 || meanTravelTimeBase <= 0) {
			log.fatal("Mean travel time for either base ({}) or policy case ({}) are zero. Mean travel velocity cannot" +
				"be calculated! Divison by 0 not possible!", meanTravelTimeBase, meanTravelTimePolicy);
			throw new IllegalArgumentException();
		}

//		write mean stats to csv
		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("mean_travel_stats.csv").toString()), getCsvFormat())) {
			printer.printRecord("\"" + policy + " users (10pct)\"", f.format(persons.rowCount()));
			printer.printRecord("\"" + policy + " trips (10pct)\"", f.format(trips.rowCount()));
			printer.printRecord("\"mean travel time base case [s]\"", f.format(meanTravelTimeBase));
			printer.printRecord("\"mean travel time policy case [s]\"", f.format(meanTravelTimePolicy));
			printer.printRecord("\"mean travel time diff [s]\"", f.format(meanTravelTimeDiff));
			printer.printRecord("\"mean travel distance base case [m]\"", f.format(meanTravelDistanceBase));
			printer.printRecord("\"mean travel distance policy case [m]\"", f.format(meanTravelDistancePolicy));
			printer.printRecord("\"mean travel distance diff [m]\"", f.format(meanTravelDistanceDiff));
			printer.printRecord("\"mean trip velocity base case [m/s]\"", f.format(meanVelocityBase));
			printer.printRecord("\"mean trip velocity policy case [m/s]\"", f.format(meanVelocityPolicy));
			printer.printRecord("\"mean trip velocity diff [m/s]\"", f.format(meanVelocityDiff));
			printer.printRecord("\"mean euclidean distance base case [m]\"", f.format(meanEuclideanDistanceBase));
			printer.printRecord("\"mean euclidean distance policy case [m]\"", f.format(meanEuclideanDistancePolicy));
			printer.printRecord("\"mean score base case [util]\"", f.format(meanScoreBase));
			printer.printRecord("\"mean score policy case [util]\"", f.format(meanScorePolicy));
			printer.printRecord("\"mean score diff [util]\"", f.format(meanScoreDiff));
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
