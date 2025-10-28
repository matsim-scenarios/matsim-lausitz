package org.matsim.run.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.api.experimental.events.handler.TeleportationArrivalEventHandler;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(name = "monetary-utility", description = "List and compare fare, dailyRefund and utility values for agents in base and policy case.")
public class AgentWiseUtilityComparison implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AgentWiseUtilityComparison.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which analysis should be performed.")
	private List<Path> inputPaths;

	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;

	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional. This can be a list of multiple prefixes. " +
		"Number of prefixes has to be equal to number of inputPaths and the list of prefixes has to have the same order as inputPaths.", split = ",")
	private List<String> prefixList = new ArrayList<>();

	private static final String POLICY = "policy";
	private static final String BASE = "base";

	public static void main(String[] args) {
		new AgentWiseUtilityComparison().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		if (!prefixList.isEmpty() && prefixList.size() != inputPaths.size()) {
			log.error("The numbers of prefixes {} and input paths {} do not match.", prefixList, inputPaths);
			return 2;
		}

		List<String> eventsFiles = new ArrayList<>();

		if (!prefixList.isEmpty()) {
			for (String prefix : prefixList) {
				eventsFiles.add("*" + prefix + "output_events_filtered.xml.gz");
			}
		} else {
			eventsFiles.add("*output_events.xml.gz");
		}

		String baseNetworkFile = globFile(basePath, "*output_network.xml.gz").toString();
		String basePopulationFile = globFile(basePath, "*output_plans.xml.gz").toString();
		String baseConfigFile = globFile(basePath, "*output_config.xml").toString();
		String baseVehiclesFile = globFile(basePath, "*output_vehicles.xml.gz").toString();

		Network baseNetwork = NetworkUtils.readNetwork(baseNetworkFile);
		Config config = ConfigUtils.loadConfig(baseConfigFile);
		Population basePopulation = PopulationUtils.readPopulation(basePopulationFile);
		cleanPopulation( basePopulation );

//		we need vehicles to track distances of modes in link leave events. link leave events do not have attr mode.
		Vehicles baseVehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader.VehicleReader(baseVehicles).readFile(baseVehiclesFile);

//		The following assumes that betaMoney and mode params are the same for base and policy.
//		if we want to implement policies involving changes in the below values, we have to do the calculation in the big for loop below.
//		ScoringConfigGroup.ModeParams carParams = config.scoring().getModes().get(TransportMode.car);
//		ScoringConfigGroup.ModeParams rideParams = config.scoring().getModes().get(TransportMode.ride);
		Map<String, ScoringConfigGroup. ModeParams> modeParams = config.scoring().getModes();

		double generalBetaMoney = config.scoring().getMarginalUtilityOfMoney();

//		double carDailyMonetaryConstant = carParams.getDailyMonetaryConstant();
//		double carMonetaryDistanceRate = carParams.getMonetaryDistanceRate();
//		double rideMonetaryDistanceRate = rideParams.getMonetaryDistanceRate();

		AtomicReference<Double> sumIncome = new AtomicReference<>(0.);
		AtomicInteger count = new AtomicInteger(0);

//		filter for person agents and calc mean income
		basePopulation.getPersons().values()
			.stream()
			.filter(p -> p.getAttributes().getAttribute("subpopulation").equals("person"))
			.forEach(person -> {
				sumIncome.set(sumIncome.get() + PersonUtils.getIncome(person));
				count.set(count.get() + 1);
			});

		double meanIncome = sumIncome.get() / count.get();
		log.info("##############################################################################################################################");
		log.info("Mean monthly income of person agents: {}€", meanIncome);
		log.info("##############################################################################################################################");

//		calc person specific beta moneys
		Map<Id<Person>, Double> betaMoneyMap = new HashMap<>();
		basePopulation.getPersons().values()
			.stream()
			.filter(p -> p.getAttributes().getAttribute("subpopulation").equals("person"))
			.forEach(person -> betaMoneyMap.put(person.getId(), generalBetaMoney * (meanIncome / PersonUtils.getIncome(person))));

		Map<String, Map<Id<Person>, SimulationData>> pattern2DataMap = new HashMap<>();

		for (String pattern : eventsFiles) {
			String baseEventsFile = globFile(basePath, pattern).toString();

//			read base case events
			Map<Id<Person>, SimulationData> baseDataMap = new HashMap<>();

			memorizeScoresFromPlans( basePopulation, baseDataMap );

			UtilityEventHandler baseHandler = new UtilityEventHandler(baseDataMap, baseNetwork, baseVehicles, modeParams.keySet());
			EventsManager baseManager = EventsUtils.createEventsManager();
			baseManager.addHandler(baseHandler);
			baseManager.addHandler( new ActivityDetectionHandler( baseDataMap ) );
			baseManager.addHandler( new ModeDetectionHandler( baseDataMap ) );
			baseManager.initProcessing();

			MatsimEventsReader baseReader = new MatsimEventsReader(baseManager);
			baseReader.readFile(baseEventsFile);
			baseManager.finishProcessing();

			pattern2DataMap.put(pattern, baseDataMap);
		}

		if (eventsFiles.size() == 1) {
			String pattern = eventsFiles.getFirst();

			Map<Id<Person>, SimulationData> baseDataMap = pattern2DataMap.get(pattern);

			for (Path inputPath : inputPaths) {
				processBaseAndPolicyData(inputPath, pattern, baseDataMap, betaMoneyMap, modeParams);
			}
		} else if (eventsFiles.size() > 1) {
			for (String pattern : eventsFiles) {
				Map<Id<Person>, SimulationData> baseDataMap = pattern2DataMap.get(pattern);

				Path correspondingPath = inputPaths.get(eventsFiles.indexOf(pattern));
				processBaseAndPolicyData(correspondingPath, pattern, baseDataMap, betaMoneyMap, modeParams);
			}
		}
		return 0;
	}
	private static void cleanPopulation( Population basePopulation ){
		List<Id<Person>> personsToRemove = new ArrayList<>();
		for( Person person : basePopulation.getPersons().values() ){
			if ( !"person".equals( PopulationUtils.getSubpopulation(person) ) ) {
				personsToRemove.add( person.getId() );
			}
		}
		for( Id<Person> personId : personsToRemove ){
			basePopulation.removePerson( personId );
		}
	}

	private static void processBaseAndPolicyData(Path inputPath, String pattern, Map<Id<Person>, SimulationData> baseDataMap,
										  Map<Id<Person>, Double> betaMoneyMap, Map<String, ScoringConfigGroup.ModeParams> modeParams) throws IOException {
		log.info("Running on {}", inputPath);
//			read policy case events
		String eventsFile = globFile(inputPath, pattern).toString();
		String networkFile = globFile(inputPath, "*output_network.xml.gz").toString();
		String vehiclesFile = globFile(inputPath, "*output_vehicles.xml.gz").toString();
		String populationFile = globFile(inputPath, "*output_plans.xml.gz").toString();


		Network network = NetworkUtils.readNetwork(networkFile);
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles).readFile(vehiclesFile);
		Population population = PopulationUtils.readPopulation(populationFile);
		cleanPopulation( population );

		Map<Id<Person>, SimulationData> policyDataMap = new HashMap<>();

		memorizeScoresFromPlans( population, policyDataMap );

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new UtilityEventHandler(policyDataMap, network, vehicles, modeParams.keySet()));
		manager.addHandler( new ModeDetectionHandler(policyDataMap) );
		manager.addHandler( new ActivityDetectionHandler( policyDataMap ) );
		MatsimEventsReader policyReader = new MatsimEventsReader(manager);
		policyReader.readFile(eventsFile);
		manager.finishProcessing();

		//			bring base and policy maps together
		Map<Id<Person>, Map<String, SimulationData>> combinedData = new HashMap<>();

		for (Map.Entry<Id<Person>, SimulationData> entry : baseDataMap.entrySet()) {
			combinedData.put(entry.getKey(), new HashMap<>());
			combinedData.get(entry.getKey()).put(BASE, entry.getValue());
		}

		for (Map.Entry<Id<Person>, SimulationData> entry : policyDataMap.entrySet()) {
//				if combined map does not contain policy person, the person did not use car or pt or ride in base case
//				thus, we add base case agent with 0 values
//			TODO: we now register all modes, so the following if condition should not be necessary anymore!
//			if (!combinedData.containsKey(entry.getKey())) {
//				combinedData.put(entry.getKey(), new HashMap<>());
//				combinedData.get(entry.getKey()).put(BASE, new SimulationData(entry.getKey(), 0., new ArrayList<>(), new ArrayList<>(), 0., 0., 0.));
//			}
			combinedData.get(entry.getKey()).put(POLICY, entry.getValue());
		}

		if (!(combinedData.size() == policyDataMap.size() && combinedData.size() == baseDataMap.size())) {
			log.fatal("In base case {} agents were registered, but in policy case {} agents were registered!", baseDataMap.size(), policyDataMap.size());
			throw new IllegalStateException();
		}


//			the remaining fare data maps in combinedFareData with 1 entry only are agents who used car/pt/ride in base case
//			but no car/pt/drt/ride in policy. we add null values for them in policy case
//		for (Map.Entry<Id<Person>, Map<String, SimulationData>> entry : combinedData.entrySet()) {
//			if (entry.getValue().size() == 1) {
//				combinedData.get(entry.getKey()).put(POLICY, new SimulationData(entry.getKey(), 0., new ArrayList<>(), new ArrayList<>(), 0., 0., 0.));
//			} else if (entry.getValue().isEmpty() || entry.getValue().size() > 2) {
//				log.fatal("Size of fare data element map should be 1 or 2 but is {}! Please check your data.", entry.getValue().size());
//				throw new IllegalStateException();
//			}
//		}

		writeCsvs(inputPath, combinedData, betaMoneyMap, modeParams, pattern);
	}
	private static void memorizeScoresFromPlans( Population population, Map<Id<Person>, SimulationData> policyDataMap ){
		for( Person person : population.getPersons().values() ){
			policyDataMap.putIfAbsent( person.getId(), new SimulationData( person.getId() ) );
			policyDataMap.get( person.getId() ).scoreFromPlan = person.getSelectedPlan().getScore();
		}
	}

	private static void writeCsvs(Path inputPath, Map<Id<Person>, Map<String, SimulationData>> combinedData, Map<Id<Person>, Double> betaMoneyMap,
								  Map<String, ScoringConfigGroup.ModeParams> modeParams, String pattern) throws IOException {
		double subtotalFareCostBaseAggr = 0.;
		double subtotalFareCostPolicyAggr = 0.;
		double subtotalFareCostDeltaAggr = 0.;
		double carCostBaseAggr = 0.;
		double carCostPolicyAggr = 0.;
		double carCostDeltaAggr = 0.;
		double rideCostBaseAggr = 0.;
		double rideCostPolicyAggr = 0.;
		double rideCostDeltaAggr = 0.;
		double totalCostBaseAggr = 0.;
		double totalCostPolicyAggr = 0.;
		double totalCostDeltaAggr = 0.;
		double utilityBaseAggr = 0.;
		double utilityPolicyAggr = 0.;
		double utilityDeltaAggr = 0.;

//		reverse engineer prefix for output
		String prefix = pattern.split("/*", 2)[1].split("output")[0];

//			write agent wise output
		String outputAgentWise = inputPath.resolve(prefix + "output_agent_wise_cost_comparison_to_base.tsv").toString();

//		TODO: we need daily fare disut, daily "normal" cost disut, daily travel disutility, daily distance disut, daily transfer disut
		List<String> headers = new ArrayList<>();
		headers.add("personId");
		headers.add("betaMoney_util_per_eu");
		headers.add("fareUtilityBase_util");
		headers.add("fareUtilityPolicy_util");
		headers.add("fareUtilityDelta_util");
		headers.add("refundUtilityBase_util");
		headers.add("refundUtilityPolicy_util");
		headers.add("refundUtilityDelta_util");
		headers.add("subtotalFareUtilityBase_util");
		headers.add("subtotalFareUtilityPolicy_util");
		headers.add("subtotalFareUtilityDelta_util");
		headers.add("farePurposeBase");
		headers.add("farePurposesPolicy");
		headers.add("fareTypesBase");
		headers.add("fareTypesPolicy");
		headers.add("subtotalDailyCostUtilityBase_util");
		headers.add("subtotalDailyCostUtilityPolicy_util");
		headers.add("subtotalDailyCostUtilityDelta_util");

//		convert modeParams to TreeMap to ensure same iteration order every time we iterate
		Map<String, ScoringConfigGroup.ModeParams> sortedModeParams = new TreeMap<>(modeParams);

		for (String m : sortedModeParams.keySet()) {
			headers.add(m + "TravelUtilityBase_util");
			headers.add(m + "TravelUtilityPolicy_util");
			headers.add(m + "DistanceUtilityBase_util");
			headers.add(m + "DistanceUtilityPolicy_util");
			headers.add(m + "ASCUtilityBase_util");
			headers.add(m + "ASCUtilityPolicy_util");
			headers.add(m + "SubtotalTravelUtilityBase_util");
			headers.add(m + "SubtotalTravelUtilityPolicy_util");
		}
		headers.add("agentTravelUtilityBase_util");
		headers.add("agentTravelUtilityPolicy_util");
		headers.add("agentTravelUtilityDelta_util");
		headers.add("agentDistanceUtilityBase_util");
		headers.add("agentDistanceUtilityPolicy_util");
		headers.add("agentDistanceUtilityDelta_util");
		headers.add("agentASCUtilityBase_util");
		headers.add("agentASCUtilityPolicy_util");
		headers.add("agentASCUtilityDelta_util");
		headers.add("agentSubtotalTravelUtilityBase_util");
		headers.add("agentSubtotalTravelUtilityPolicy_util");
		headers.add("agentSubtotalTravelUtilityDelta_util");
		headers.add("agentTotalUtilityBase_util");
		headers.add("agentTotalUtilityPolicy_util");
		headers.add("agentTotalUtilityDelta_util");

		CSVFormat format = CSVFormat.DEFAULT.builder()
			.setQuote(null)
			.setDelimiter(',')
			.setRecordSeparator("\r\n")
			.setHeader(headers.toArray(new String[0]))
			.build();

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputAgentWise), format)) {

//			TODO: delete this header after header creation above is complete
//			printer.printRecord("personId", "betaMoney_util_eu",
//				"fareBase_eu", "farePolicy_eu", "fareDelta_eu",
//				"refundBase_eu", "refundPolicy_eu", "refundDelta_eu",
//				"subtotalFareBase_eu", "subtotalFarePolicy_eu", "subtotalFareDelta_eu",
//				"farePurposeBases", "farePurposesPolicy",
//				"fareTypesBase", "fareTypesPolicy",
//				"carDistanceBase_m", "carDistancePolicy_m",
//				"carCostBase_eu", "carCostPolicy_eu", "carCostDelta_eu",
//				"rideDistanceBase_m", "rideDistancePolicy_m",
//				"rideCostBase_eu", "rideCostPolicy_eu", "rideCostDelta_eu",
//				"totalCostBase_eu", "totalCostPolicy_eu", "totalCostDelta_eu",
//				"utilityBase_util", "utilityPolicy_util", "utilityDelta_util");

			for (Map.Entry<Id<Person>, Map<String, SimulationData>> entry : combinedData.entrySet()) {
				SimulationData baseData = entry.getValue().get(BASE);
				SimulationData policyData = entry.getValue().get(POLICY);

				double scoreFromPlansDelta = policyData.scoreFromPlan - baseData.scoreFromPlan;

				double subtotalFareBase = baseData.dailyFareCost + baseData.dailyFareRefund;
				double subtotalFarePolicy = policyData.dailyFareCost + policyData.dailyFareRefund;
				double subtotalFareDelta = subtotalFarePolicy - subtotalFareBase;

				double personSpecificBetaMoney = betaMoneyMap.get(entry.getKey());

				Map<String, Double> policyModeDailyCost = new LinkedHashMap<>();
				Map<String, Double> policyModeDistanceUtility = new LinkedHashMap<>();
				Map<String, Double> policyModeTimeUtility = new LinkedHashMap<>();
				Map<String, Double> policyModeASC = new LinkedHashMap<>();
				Map<String, Double> baseModeDailyCost = new LinkedHashMap<>();
				Map<String, Double> baseModeDistanceUtility = new LinkedHashMap<>();
				Map<String, Double> baseModeTimeUtility = new LinkedHashMap<>();
				Map<String, Double> baseModeASC = new LinkedHashMap<>();
				for (Map.Entry<String, ScoringConfigGroup.ModeParams> e : sortedModeParams.entrySet()) {
					policyModeDailyCost.put(e.getKey(), calcDailyModeCost(policyData, e.getValue()));
					policyModeDistanceUtility.put(e.getKey(), calcModeDistanceUtility(policyData, e.getValue()));
					policyModeTimeUtility.put(e.getKey(), calcModeTravelUtility(policyData, e.getValue()));
					policyModeASC.put(e.getKey(), calcModeASCUtility(policyData, e.getValue()));
					baseModeDailyCost.put(e.getKey(), calcDailyModeCost(baseData, e.getValue()));
					baseModeDistanceUtility.put(e.getKey(), calcModeDistanceUtility(baseData, e.getValue()));
					baseModeTimeUtility.put(e.getKey(), calcModeTravelUtility(baseData, e.getValue()));
					baseModeASC.put(e.getKey(), calcModeASCUtility(baseData, e.getValue()));
				}



//				TODO: make this mode dependent. Rather create map with cost than single doubles?
//				double carCostBase = policyModeDailyCost.get(TransportMode.car);
//				double carCostPolicy = calcDailyModeCost(policyData, carDailyMonetaryConstant, carMonetaryDistanceRate);
//				double carCostDelta = carCostPolicy - carCostBase;
//				double rideCostBase = calcDailyRideCost(baseData, rideMonetaryDistanceRate);
//				double rideCostPolicy = calcDailyRideCost(policyData, rideMonetaryDistanceRate);
//				double rideCostDelta = rideCostPolicy - rideCostBase;
//				double totalCostBase = carCostBase + subtotalFareBase + rideCostBase;
//				double totalCostPolicy = carCostPolicy + subtotalFarePolicy + rideCostPolicy;
//				double totalCostDelta = totalCostPolicy - totalCostBase;
//				double utilityBase = totalCostBase * personSpecificBetaMoney;
//				double utilityPolicy = totalCostPolicy * personSpecificBetaMoney;
//				double utilityDelta = utilityPolicy - utilityBase;

				final double fareDelta = policyData.dailyFareCost - baseData.dailyFareCost;
				final double refundDelta = policyData.dailyFareRefund - baseData.dailyFareRefund;
				final double baseDailyCostSum = sum(baseModeDailyCost.values());
				final double policyDailyCostSum = sum(policyModeDailyCost.values());


				final String basePurposes = String.join( "--", baseData.purposes );
				final String policyPurposes = String.join( "-", policyData.purposes );
				final String baseModes = String.join( "--", baseData.modeList );
				final String policyModes = String.join( "--", policyData.modeList );
				final String baseActivities = String.join( " | ", baseData.activites );
				final String policyActivities = String.join( " | ", policyData.activites );

				List<Object> recordList = new ArrayList<>();
				recordList.add(entry.getKey().toString());
				recordList.add(personSpecificBetaMoney);
				recordList.add(baseData.dailyFareCost);
				recordList.add(policyData.dailyFareCost);
				recordList.add(fareDelta);
				recordList.add(baseData.dailyFareRefund);
				recordList.add(policyData.dailyFareRefund);
				recordList.add(refundDelta);
				recordList.add(subtotalFareBase);
				recordList.add(subtotalFarePolicy);
				recordList.add(subtotalFareDelta);
				recordList.add(basePurposes);
				recordList.add(policyPurposes);
				recordList.add(String.join("-", baseData.fareTypes));
				recordList.add(String.join("-", policyData.fareTypes));
				recordList.add(baseDailyCostSum);
				recordList.add(policyDailyCostSum);
				recordList.add(policyDailyCostSum - baseDailyCostSum);

				for (String m : sortedModeParams.keySet()) {
					double baseTimeUtility = baseModeTimeUtility.get(m);
					double policyTimeUtility = policyModeTimeUtility.get(m);
					double baseDistanceUtility = baseModeDistanceUtility.get(m);
					double policyDistanceUtility = policyModeDistanceUtility.get(m);
					double baseASCUtility = baseModeASC.get(m);
					double policyASCUtility = policyModeASC.get(m);

					recordList.add(baseTimeUtility);
					recordList.add(policyTimeUtility);
					recordList.add(baseDistanceUtility);
					recordList.add(policyDistanceUtility);
					recordList.add(baseASCUtility);
					recordList.add(policyASCUtility);
					recordList.add(baseTimeUtility + baseDistanceUtility + baseASCUtility);
					recordList.add(policyTimeUtility + policyDistanceUtility + policyASCUtility);
				}

				double baseTravelUtilitySum = sum(baseModeTimeUtility.values());
				double policyTravelUtilitySum = sum(policyModeTimeUtility.values());
				double baseDistanceUtilitySum = sum(baseModeDistanceUtility.values());
				double policyDistanceUtilitySum = sum(policyModeDistanceUtility.values());
				double baseASCUtilitySum = sum(baseModeASC.values());
				double policyASCUtilitySum = sum(policyModeASC.values());
				double baseSubtotalUtility = baseTravelUtilitySum + baseDistanceUtilitySum + baseASCUtilitySum;
				double policySubtotalUtility = policyTravelUtilitySum + policyDistanceUtilitySum + policyASCUtilitySum;

				double totalUtilityBase = subtotalFareBase + baseDailyCostSum + baseSubtotalUtility;
				double totalUtilityPolicy = subtotalFarePolicy + policyDailyCostSum + policySubtotalUtility;

				recordList.add(baseTravelUtilitySum);
				recordList.add(policyTravelUtilitySum);
				recordList.add(policyTravelUtilitySum - baseTravelUtilitySum);
				recordList.add(baseDistanceUtilitySum);
				recordList.add(policyDistanceUtilitySum);
				recordList.add(policyDistanceUtilitySum - baseDistanceUtilitySum);
				recordList.add(baseASCUtilitySum);
				recordList.add(policyASCUtilitySum);
				recordList.add(policyASCUtilitySum - baseASCUtilitySum);
				recordList.add(baseSubtotalUtility);
				recordList.add(policySubtotalUtility);
				recordList.add(policySubtotalUtility - baseSubtotalUtility);
				recordList.add(totalUtilityBase);
				recordList.add(totalUtilityPolicy);
				recordList.add(totalUtilityPolicy - totalUtilityBase);

				printer.printRecord(recordList.toArray());

//				printer.printRecord(entry.getKey().toString(), personSpecificBetaMoney,
//						baseData.dailyCost, policyData.dailyCost, fareDelta,
//						baseData.dailyRefund, policyData.dailyRefund, refundDelta,
//						subtotalFareBase, subtotalFarePolicy, subtotalFareDelta,
//						basePurposes, policyPurposes,
//						String.join("-", baseData.fareTypes), String.join("-", policyData.fareTypes),
//						baseData.dailyCarDistance, policyData.dailyCarDistance,
//						carCostBase, carCostPolicy, carCostDelta,
//						baseData.dailyRideDistance, policyData.dailyRideDistance,
//						rideCostBase, rideCostPolicy, rideCostDelta,
//						totalCostBase, totalCostPolicy, totalCostDelta,
//						utilityBase, utilityPolicy, utilityDelta
//								   );

				if ( isTestPerson( entry.getKey() ) ) {
					log.warn("personId={}; scoreFromPlansDelta={}", entry.getKey(), scoreFromPlansDelta );
					log.warn( "baseActivities={}", baseActivities );
					log.warn( "policyActivites={}", policyActivities );
					log.warn( "baseModes={}", baseModes );
					log.warn( "policyModes={}", policyModes );
//					log.warn("carCostDelta={}; rideCostDelta={}; fareDelta={}; refundDelta={}", carCostDelta, rideCostDelta, fareDelta, refundDelta );
					log.warn("about to exit ...");
					System.exit(-1);
				}

//				subtotalFareCostBaseAggr += subtotalFareBase;
//				subtotalFareCostPolicyAggr += subtotalFarePolicy;
//				subtotalFareCostDeltaAggr += subtotalFareDelta;
//				carCostBaseAggr += carCostBase;
//				carCostPolicyAggr += carCostPolicy;
//				carCostDeltaAggr += carCostDelta;
//				rideCostBaseAggr += rideCostBase;
//				rideCostPolicyAggr += rideCostPolicy;
//				rideCostDeltaAggr += rideCostDelta;
//				totalCostBaseAggr += totalCostBase;
//				totalCostPolicyAggr += totalCostPolicy;
//				totalCostDeltaAggr += totalCostDelta;
//				utilityBaseAggr += utilityBase;
//				utilityPolicyAggr += utilityPolicy;
//				utilityDeltaAggr += utilityDelta;
			}
		}

//			write aggregated output
		String outputAggr = inputPath.resolve(prefix + "output_aggregated_cost_comparison_to_base.tsv").toString();

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputAggr), PtLineAnalysis.getCsvFormat())) {
			printer.printRecord("subtotalFareCostBaseAggr", "subtotalFareCostPolicyAggr", "carCostBaseAggr", "carCostPolicyAggr",
				"rideCostBaseAggr", "rideCostPolicyAggr", "totalCostBaseAggr", "totalCostPolicyAggr", "utilityBaseAggr", "utilityPolicyAggr");

			printer.printRecord(subtotalFareCostBaseAggr, subtotalFareCostPolicyAggr, carCostBaseAggr, carCostPolicyAggr,
				rideCostBaseAggr, rideCostPolicyAggr, totalCostBaseAggr, totalCostPolicyAggr, utilityBaseAggr, utilityPolicyAggr);
		}

//			write mean output
		String outputMean = inputPath.resolve(prefix + "output_mean_cost_comparison_to_base.tsv").toString();

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputMean), PtLineAnalysis.getCsvFormat())) {
			printer.printRecord("subtotalFareCostBaseMean", "subtotalFareCostPolicyMean", "subtotalFareCostDeltaMean",
				"carCostBaseMean", "carCostPolicyMean", "carCostDeltaMean",
				"rideCostBaseMean", "rideCostPolicyMean", "rideCostDeltaMean",
				"totalCostBaseMean", "totalCostPolicyMean", "totalCostDeltaMean",
				"utilityBaseMean", "utilityPolicyMean", "utilityDeltaMean");

			int size = combinedData.size();

			printer.printRecord(subtotalFareCostBaseAggr / size, subtotalFareCostPolicyAggr / size, subtotalFareCostDeltaAggr / size,
				carCostBaseAggr / size, carCostPolicyAggr / size, carCostDeltaAggr / size,
				rideCostBaseAggr / size, rideCostPolicyAggr / size, rideCostDeltaAggr / size,
				totalCostBaseAggr / size, totalCostPolicyAggr / size, totalCostDeltaAggr / size,
				utilityBaseAggr / size, utilityPolicyAggr / size, utilityDeltaAggr / size);
		}
	}

	private static double sum(Collection<Double> doubles) {
		return doubles.stream().mapToDouble(Double::doubleValue).sum();
	}

	private static boolean isTestPerson( Id<Person> personId ){
		return "297374".equals( personId.toString() );
	}

	private static double calcDailyModeCost(SimulationData data, ScoringConfigGroup.ModeParams modeParams) {
		String mode = modeParams.getMode();
		Double dailyModeDistance = data.dailyModeDistances.get(mode);
		if ( dailyModeDistance==null ) {
			return 0.;
		}

		if (dailyModeDistance > 0.) {
			return modeParams.getDailyMonetaryConstant() + dailyModeDistance * modeParams.getMonetaryDistanceRate();
		} else {
			return 0.;
		}
	}

	private static double calcModeDistanceUtility(SimulationData data, ScoringConfigGroup.ModeParams modeParams) {
		String mode = modeParams.getMode();
		Double dailyModeDistance = data.dailyModeDistances.get(mode);
		if ( dailyModeDistance==null ) {
			return 0.;
		}

		if (dailyModeDistance > 0.) {
			return dailyModeDistance * modeParams.getMarginalUtilityOfDistance();
		} else {
			return 0.;
		}
	}

	private static double calcModeTravelUtility(SimulationData data, ScoringConfigGroup.ModeParams modeParams) {
		String mode = modeParams.getMode();
		Double dailyModeTravelTime = data.dailyModeTravelTimes.get(mode);
		if ( dailyModeTravelTime==null ) {
			return 0.;
		}

		if (dailyModeTravelTime > 0.) {
			return dailyModeTravelTime / 3600 * modeParams.getMarginalUtilityOfTraveling();
		} else {
			return 0.;
		}
	}

	private static double calcModeASCUtility(SimulationData data, ScoringConfigGroup.ModeParams modeParams) {
		String mode = modeParams.getMode();
		Integer dailyModeLegCount = data.dailyModeLegCount.get(mode);
		if ( dailyModeLegCount==null ) {
			return 0;
		}

		if (dailyModeLegCount > 0) {
			return dailyModeLegCount * modeParams.getConstant();
		} else {
			return 0.;
		}
	}

	//	I do not understand what this handler is necessary for -sm1025
	// you can put everything into one event handler.  or you have different ones. kai
	private static final class ModeDetectionHandler implements PersonDepartureEventHandler {
		private final Map<Id<Person>, SimulationData> dataMap;
		public ModeDetectionHandler( Map<Id<Person>, SimulationData> simulationData ){
			this.dataMap = simulationData;
		}
		@Override public void handleEvent( PersonDepartureEvent event ){
			this.dataMap.putIfAbsent( event.getPersonId(), new SimulationData( event.getPersonId() ) );
			SimulationData simulationData = this.dataMap.get( event.getPersonId() );
			simulationData.addToModeList( event.getLegMode() );
//			if ( isTestPerson( event.getPersonId() ) ){
//				log.warn( "personId={}; just added mode={}; new mode list={}", event.getPersonId(), event.getLegMode(), String.join( "-", simulationData.modeList ) );
//			}
		}
	}

	private static final class ActivityDetectionHandler implements ActivityStartEventHandler {

		private final Map<Id<Person>, SimulationData> dataMap;
		public ActivityDetectionHandler( Map<Id<Person>, SimulationData> dataMap ){
			this.dataMap = dataMap;
		}
		@Override public void handleEvent( ActivityStartEvent event ){
			final String actType = event.getActType();
			if ( TripStructureUtils.isStageActivityType( actType ) ) {
				return;
			}
			this.dataMap.putIfAbsent( event.getPersonId(), new SimulationData( event.getPersonId() ) );
			SimulationData simulationData = this.dataMap.get( event.getPersonId() );
			simulationData.addToActivityList( actType );
			if ( isTestPerson( event.getPersonId() ) ){
				log.warn( "personId={}; just added activity={}; new activity list={}", event.getPersonId(), actType, String.join( " | ", simulationData.activites ) );
			}
		}
	}


	private static final class UtilityEventHandler implements PersonMoneyEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler,
		LinkLeaveEventHandler, TeleportationArrivalEventHandler, PersonDepartureEventHandler {
		private final Map<Id<Person>, SimulationData> dataMap;
		private final Network network;
		private final Vehicles vehicles;
		private final Set<String> modes;

		private final Map<Id<Vehicle>, Id<Person>> vehicle2DriverInTraffic = new HashMap<>();
		private final Map<Id<Person>, Double> personDepartures = new HashMap<>();

		UtilityEventHandler(Map<Id<Person>, SimulationData> dataMap, Network network, Vehicles vehicles, Set<String> modes) {
			this.dataMap = dataMap;
			this.network = network;
			this.vehicles = vehicles;
			this.modes = modes;
		}

		@Override
		public void handleEvent(LinkLeaveEvent event) {
//			we only care about vehicles/drivers in the below map = person agents != freight agents.
			if (vehicle2DriverInTraffic.containsKey(event.getVehicleId())) {
				Id<Person> personId = vehicle2DriverInTraffic.get(event.getVehicleId());

				dataMap.put(personId, dataMap.get(personId)
					.updateDailyModeDistance(vehicles.getVehicles().get(event.getVehicleId()).getType().getId().toString(),
					network.getLinks().get(event.getLinkId()).getLength()));
			}
		}

		@Override
		public void handleEvent(PersonMoneyEvent event) {
//			event structure:
//			<event time="31181.0" type="personMoney" person="hoyerswerdaOnly" dailyCost="-3.0" purposes="pt fare" transactionPartner="VVO Tarifzone 20" reference="hoyerswerdaOnly"  />

			if (event.getPurpose().contains("fare")) {
				if (!event.getPurpose().contains("refund")) {
//					if not refund, we are handling a fare
					dataMap.put(event.getPersonId(), dataMap.get(event.getPersonId())
						.updateDailyCost(event.getAmount())
						.updatePurposeList(event.getPurpose())
						.updateFareTypesList(event.getTransactionPartner()));
				} else {
//					dailyRefund
					dataMap.put(event.getPersonId(), dataMap.get(event.getPersonId())
						.updateDailyRefund(event.getAmount())
						.updatePurposeList(event.getPurpose())
						.updateFareTypesList(event.getTransactionPartner()));
				}
			}
		}

		@Override
		public void handleEvent(VehicleEntersTrafficEvent event) {
//			register vehicle of person
			if (personDepartures.containsKey(event.getPersonId())) {
				vehicle2DriverInTraffic.put(event.getVehicleId(), event.getPersonId());
			}
		}

		@Override
		public void handleEvent(VehicleLeavesTrafficEvent event) {
//			last link has no LinkLeaveEvent. register the distance here.
			if (vehicle2DriverInTraffic.containsKey(event.getVehicleId())) {
				Id<Person> personId = event.getPersonId();
				String mode = event.getNetworkMode();

				double travelTime = event.getTime() - personDepartures.get(personId);

//				TODO: test if this concatenation works
				dataMap.put(personId, dataMap.get(personId)
					.updateDailyModeDistance(mode, network.getLinks().get(event.getLinkId()).getLength())
					.updateDailyModeTravelTime(mode, travelTime)
					.updateDailyModeLegCount(mode));
				vehicle2DriverInTraffic.remove(event.getVehicleId());
				personDepartures.remove(personId);
			}
		}

		@Override
		public void handleEvent(TeleportationArrivalEvent event) {
				Id<Person> personId = event.getPersonId();
				String mode = event.getMode();

				double travelTime = event.getTime() - personDepartures.get(personId);

				dataMap.put(personId, dataMap.get(personId)
					.updateDailyModeDistance(mode, event.getDistance())
					.updateDailyModeTravelTime(mode, travelTime)
					.updateDailyModeLegCount(mode));
				personDepartures.remove(personId);
		}

		@Override
		public void handleEvent(PersonDepartureEvent event) {
//			detect departure time of person. departure events are thrown for network legs and teleported legs as well.
//			we only care about person agents != freight agents.
			Id<Person> personId = event.getPersonId();
			if (!(personId.toString().contains("goods") || personId.toString().contains("commercial") || personId.toString().contains("freight"))) {
				personDepartures.put(event.getPersonId(), event.getTime());

				//initialize map for mode distances and tts
				Map<String, Double> emptyModeToDoubleMap = new HashMap<>();
				Map<String, Integer> emptyModeToIntMap = new HashMap<>();
				for (String m : modes) {
					emptyModeToDoubleMap.put(m, 0.);
					emptyModeToIntMap.put(m, 0);
				}

				dataMap.putIfAbsent(event.getPersonId(),
					new SimulationData(event.getPersonId(), 0., new ArrayList<>(), new ArrayList<>(), 0,
						emptyModeToDoubleMap, emptyModeToDoubleMap, emptyModeToIntMap));
			}
		}
	}

	private static class SimulationData {
		// ("record" scheint mir hier nicht so sinnvoll; das legt ja bei jeder Änderung ein neues Objekt an.  M.E. besser eine normale static class.  kai, oct'25)

		// yyyy possibly, "purposes" was just another name for "activities".  Should be cleaned up.  kai, oct'25
//		purposes are the fare purposes, e.g. "pt fare" or "pt or drt fare". we do not really need them. -sm1025

		private final Id<Person> personId;
		public Double scoreFromPlan;
		private double dailyFareCost;
		private List<String> purposes = new ArrayList<>();
		private List<String> fareTypes = new ArrayList<>();
		private final List<String> modeList = new ArrayList<>();
		private double dailyFareRefund;
		private final List<String> activites = new ArrayList<>();
		private Map<String, Double> dailyModeDistances = new HashMap<>();
		private Map<String, Double> dailyModeTravelTimes = new HashMap<>();
		private Map<String, Integer> dailyModeLegCount = new HashMap<>();

		SimulationData( Id<Person> personId ){
			this.personId = personId;
		}

		SimulationData(Id<Person> personId, double dailyCost, List<String> purposes, List<String> fareTypes, double dailyRefund,
					   Map<String, Double> dailyModeDistances, Map<String, Double> dailyModeTravelTimes, Map<String, Integer> dailyModeLegCount){
			this.personId = personId;
			this.dailyFareCost = dailyCost;
			this.purposes = purposes;
			this.fareTypes = fareTypes;
			this.dailyFareRefund = dailyRefund;
			this.dailyModeDistances = dailyModeDistances;
			this.dailyModeTravelTimes = dailyModeTravelTimes;
			this.dailyModeLegCount = dailyModeLegCount;
		}

		public SimulationData addToModeList( String legMode ){
			modeList.add( legMode );
			return this;
		}

		private SimulationData updateDailyCost(double amount) {
			this.dailyFareCost += amount;
			return this;
		}

		private SimulationData updateDailyRefund(double refund) {
			this.dailyFareRefund += refund;
			return this;
		}

		private SimulationData updateDailyModeDistance(String mode, double distance) {
			dailyModeDistances.putIfAbsent( mode, 0. );
			this.dailyModeDistances.put(mode, dailyModeDistances.get(mode) + distance);
			return this;
		}

		private SimulationData updateDailyModeTravelTime(String mode, double travelTime) {
			dailyModeTravelTimes.putIfAbsent( mode, 0. );
			this.dailyModeTravelTimes.put(mode, dailyModeTravelTimes.get(mode) + travelTime);
			return this;
		}

		private SimulationData updateDailyModeLegCount(String mode) {
			dailyModeLegCount.putIfAbsent( mode, 0 );
			this.dailyModeLegCount.put(mode, dailyModeLegCount.get(mode) + 1);
			return this;
		}

//		private SimulationData updateDailyCarDistance(double distance) {
////			return new SimulationData(this.personId, this.dailyCost, this.purposes, this.fareTypes, this.dailyRefund, this.dailyCarDistance + distance, this.dailyRideDistance);
//			this.dailyCarDistance += distance;
//			return this;
//		}
//
//		private SimulationData updateDailyRideDistance(double distance) {
////			return new SimulationData(this.personId, this.dailyCost, this.purposes, this.fareTypes, this.dailyRefund, this.dailyCarDistance, this.dailyRideDistance + distance);
//			this.dailyRideDistance += distance;
//			return this;
//		}

		private SimulationData updatePurposeList(String purpose) {
			this.purposes.add( purpose) ;
			return this;
		}

		private SimulationData updateFareTypesList(String type) {
			this.fareTypes.add( type );
			return this;
		}
		public SimulationData addToActivityList( String actType ){
			this.activites.add( actType );
			return this;
		}
	}
}
