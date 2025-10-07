package org.matsim.run.analysis;

import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.run.analysis.PtLineAnalysis.getCsvFormat;

@CommandLine.Command(name = "monetary-utility", description = "List and compare fare, dailyRefund and utility values for agents in base and policy case.")
public class AgentWiseCostComparison implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AgentWiseCostComparison.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which analysis should be performed.")
	private List<Path> inputPaths;
	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;
	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional.", defaultValue = "")
	private String prefix;

	private static final String POLICY = "policy";
	private static final String BASE = "base";

	public static void main(String[] args) {
		new AgentWiseCostComparison().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		String pattern;
		if (!prefix.isEmpty()) {
			pattern = "*" + prefix + "output_events_filtered.xml.gz";
		} else {
			pattern = "*output_events.xml.gz";
		}

		String baseEventsFile = globFile(basePath, pattern).toString();
		String baseNetworkFile = globFile(basePath, "*output_network.xml.gz").toString();
		String basePopulationFile = globFile(basePath, "*output_plans.xml.gz").toString();
		String baseConfigFile = globFile(basePath, "*output_config.xml").toString();

		//			read base case events
		Map<Id<Person>, SimulationData> baseFareDataMap = new HashMap<>();
		Network baseNetwork = NetworkUtils.readNetwork(baseNetworkFile);
		FareEventHandler baseHandler = new FareEventHandler(baseFareDataMap, baseNetwork);

		EventsManager baseManager = EventsUtils.createEventsManager();
		baseManager.addHandler(baseHandler);
		baseManager.initProcessing();

		MatsimEventsReader baseReader = new MatsimEventsReader(baseManager);
		baseReader.readFile(baseEventsFile);
		baseManager.finishProcessing();

		Config config = ConfigUtils.loadConfig(baseConfigFile);
		Population basePopulation = PopulationUtils.readPopulation(basePopulationFile);

//		The following assumes that betaMoney and mode params are the same for base and policy.
//		if we want to implement policies involving changes in the below values, we have to do the calculation in the big for loop below.
		ScoringConfigGroup.ModeParams carParams = config.scoring().getModes().get(TransportMode.car);
		double generalBetaMoney = config.scoring().getMarginalUtilityOfMoney();

		double carDailyMonetaryConstant = carParams.getDailyMonetaryConstant();
		double carMonetaryDistanceRate = carParams.getMonetaryDistanceRate();

		AtomicReference<Double> sumIncome = new AtomicReference<>(0.);
		AtomicInteger count = new AtomicInteger(0);

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

		for (Path inputPath : inputPaths) {
			log.info("Running on {}", inputPath);
//			read policy case events
			String eventsFile = globFile(inputPath, pattern).toString();
			String networkFile = globFile(inputPath, "*output_network.xml.gz").toString();

			Network network = NetworkUtils.readNetwork(networkFile);
			Map<Id<Person>, SimulationData> policyFareDataMap = new HashMap<>();

			EventsManager manager = EventsUtils.createEventsManager();
			manager.addHandler(new FareEventHandler(policyFareDataMap, network));
			MatsimEventsReader policyReader = new MatsimEventsReader(manager);
			policyReader.readFile(eventsFile);
			manager.finishProcessing();

//			bring base and policy maps together
			Map<Id<Person>, Map<String, SimulationData>> combinedData = new HashMap<>();

			for (Map.Entry<Id<Person>, SimulationData> entry : baseFareDataMap.entrySet()) {
				combinedData.put(entry.getKey(), new HashMap<>());
				combinedData.get(entry.getKey()).put(BASE, entry.getValue());
			}

			for (Map.Entry<Id<Person>, SimulationData> entry : policyFareDataMap.entrySet()) {
//				if combined map does not contain policy person, the person did not use car or pt in base case
//				thus, we add base case agent with 0 values
				if (!combinedData.containsKey(entry.getKey())) {
					combinedData.put(entry.getKey(), new HashMap<>());
					combinedData.get(entry.getKey()).put(BASE, new SimulationData(entry.getKey(), 0., new ArrayList<>(), new ArrayList<>(), 0., 0.));
				}
				combinedData.get(entry.getKey()).put(POLICY, entry.getValue());
			}

//			the remaining fare data maps in combinedFareData with 1 entry only are agents who used car/pt in base case
//			but no car/pt/drt in policy. we add null values for them in policy case
			for (Map.Entry<Id<Person>, Map<String, SimulationData>> entry : combinedData.entrySet()) {
				if (entry.getValue().size() == 1) {
					combinedData.get(entry.getKey()).put(POLICY, new SimulationData(entry.getKey(), 0., new ArrayList<>(), new ArrayList<>(), 0., 0.));
				} else if (entry.getValue().isEmpty() || entry.getValue().size() > 2) {
					log.fatal("Size of fare data element map should be 1 or 2 but is {}! Please check your data.", entry.getValue().size());
					return 2;
				}
			}

			writeCsvs(inputPath, combinedData, betaMoneyMap, carDailyMonetaryConstant, carMonetaryDistanceRate);
		}
		return 0;
	}

	private void writeCsvs(Path inputPath, Map<Id<Person>, Map<String, SimulationData>> combinedData, Map<Id<Person>, Double> betaMoneyMap, double carDailyMonetaryConstant, double carMonetaryDistanceRate) throws IOException {
		double subtotalFareCostBaseAggr = 0.;
		double subtotalFareCostPolicyAggr = 0.;
		double subtotalFareCostDeltaAggr = 0.;
		double carCostBaseAggr = 0.;
		double carCostPolicyAggr = 0.;
		double carCostDeltaAggr = 0.;
		double totalCostBaseAggr = 0.;
		double totalCostPolicyAggr = 0.;
		double totalCostDeltaAggr = 0.;
		double utilityBaseAggr = 0.;
		double utilityPolicyAggr = 0.;
		double utilityDeltaAggr = 0.;

//			write agent wise output
		String outputAgentWise = inputPath.resolve(prefix + "output_agent_wise_cost_comparison_to_base.tsv").toString();

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputAgentWise), getCsvFormat())) {
			printer.printRecord("personId", "betaMoney",
				"fareBase", "farePolicy", "fareDelta",
				"refundBase", "refundPolicy", "refundDelta",
				"subtotalFareBase", "subtotalFarePolicy", "subtotalFareDelta",
				"farePurposeBases", "farePurposesPolicy",
				"fareTypesBase", "fareTypesPolicy",
				"carDistanceBase", "carDistancePolicy",
				"carCostBase", "carCostPolicy", "carCostDelta",
				"totalCostBase", "totalCostPolicy", "totalCostDelta",
				"utilityBase", "utilityPolicy", "utilityDelta");

			for (Map.Entry<Id<Person>, Map<String, SimulationData>> entry : combinedData.entrySet()) {
				SimulationData baseFareData = entry.getValue().get(BASE);
				SimulationData policyFareData = entry.getValue().get(POLICY);
				double subtotalFareBase = baseFareData.dailyCost + baseFareData.dailyRefund;
				double subtotalFarePolicy = policyFareData.dailyCost + policyFareData.dailyRefund;
				double subtotalFareDelta = subtotalFarePolicy - subtotalFareBase;

				double personSpecificBetaMoney = betaMoneyMap.get(entry.getKey());

				double carCostBase = calcDailyCarCost(baseFareData, carDailyMonetaryConstant, carMonetaryDistanceRate);
				double carCostPolicy = calcDailyCarCost(policyFareData, carDailyMonetaryConstant, carMonetaryDistanceRate);
				double carCostDelta = carCostPolicy - carCostBase;
				double totalCostBase = carCostBase + subtotalFareBase;
				double totalCostPolicy = carCostPolicy + subtotalFarePolicy;
				double totalCostDelta = totalCostPolicy - totalCostBase;
				double utilityBase = totalCostBase * personSpecificBetaMoney;
				double utilityPolicy = totalCostPolicy * personSpecificBetaMoney;
				double utilityDelta = utilityPolicy - utilityBase;

				printer.printRecord(entry.getKey().toString(), personSpecificBetaMoney,
					baseFareData.dailyCost, policyFareData.dailyCost, policyFareData.dailyCost - baseFareData.dailyCost,
					baseFareData.dailyRefund, policyFareData.dailyRefund, policyFareData.dailyRefund - baseFareData.dailyRefund,
					subtotalFareBase, subtotalFarePolicy, subtotalFareDelta,
					String.join("-", baseFareData.purposes), String.join("-", policyFareData.purposes),
					String.join("-", baseFareData.fareTypes), String.join("-", policyFareData.fareTypes),
					baseFareData.dailyDistance, policyFareData.dailyDistance,
					carCostBase, carCostPolicy, carCostDelta,
					totalCostBase, totalCostPolicy, totalCostDelta,
					utilityBase, utilityPolicy, utilityDelta
					);

				subtotalFareCostBaseAggr += subtotalFareBase;
				subtotalFareCostPolicyAggr += subtotalFarePolicy;
				subtotalFareCostDeltaAggr += subtotalFareDelta;
				carCostBaseAggr += carCostBase;
				carCostPolicyAggr += carCostPolicy;
				carCostDeltaAggr += carCostDelta;
				totalCostBaseAggr += totalCostBase;
				totalCostPolicyAggr += totalCostPolicy;
				totalCostDeltaAggr += totalCostDelta;
				utilityBaseAggr += utilityBase;
				utilityPolicyAggr += utilityPolicy;
				utilityDeltaAggr += utilityDelta;
			}
		}

//			write aggregated output
		String outputAggr = inputPath.resolve(prefix + "output_aggregated_cost_comparison_to_base.tsv").toString();

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputAggr), getCsvFormat())) {
			printer.printRecord("subtotalFareCostBaseAggr", "subtotalFareCostPolicyAggr", "carCostBaseAggr", "carCostPolicyAggr",
				"totalCostBaseAggr", "totalCostPolicyAggr", "utilityBaseAggr", "utilityPolicyAggr");

			printer.printRecord(subtotalFareCostBaseAggr, subtotalFareCostPolicyAggr, carCostBaseAggr, carCostPolicyAggr,
				totalCostBaseAggr, totalCostPolicyAggr, utilityBaseAggr, utilityPolicyAggr);
		}

//			write mean output
		String outputMean = inputPath.resolve(prefix + "output_mean_cost_comparison_to_base.tsv").toString();

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputMean), getCsvFormat())) {
			printer.printRecord("subtotalFareCostBaseMean", "subtotalFareCostPolicyMean", "subtotalFareCostDeltaMean",
				"carCostBaseMean", "carCostPolicyMean", "carCostDeltaMean",
				"totalCostBaseMean", "totalCostPolicyMean", "totalCostDeltaMean",
				"utilityBaseMean", "utilityPolicyMean", "utilityDeltaMean");

			printer.printRecord(subtotalFareCostBaseAggr / combinedData.size(), subtotalFareCostPolicyAggr / combinedData.size(), subtotalFareCostDeltaAggr / combinedData.size(),
				carCostBaseAggr / combinedData.size(), carCostPolicyAggr / combinedData.size(), carCostDeltaAggr / combinedData.size(),
				totalCostBaseAggr / combinedData.size(), totalCostPolicyAggr / combinedData.size(), totalCostDeltaAggr / combinedData.size(),
				utilityBaseAggr / combinedData.size(), utilityPolicyAggr / combinedData.size(), utilityDeltaAggr / combinedData.size());
		}
	}

	private double calcDailyCarCost(SimulationData data, double carDailyMonetaryConstant, double carMonetaryDistanceRate) {
		if (data.dailyDistance > 0.) {
			return carDailyMonetaryConstant + data.dailyDistance * carMonetaryDistanceRate;
		} else {
			return 0.;
		}
	}

	private static final class FareEventHandler implements PersonMoneyEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler, LinkLeaveEventHandler {
		private final Map<Id<Person>, SimulationData> dataMap;
		private final Network network;

		private final Map<Id<Vehicle>, Id<Person>> vehicle2DriverInTraffic = new HashMap<>();

		FareEventHandler(Map<Id<Person>, SimulationData> dataMap, Network network) {
			this.dataMap = dataMap;
			this.network = network;
		}

		@Override
		public void handleEvent(LinkLeaveEvent event) {
//			we only care about vehicles/drivers in the below map = car users.
			if (vehicle2DriverInTraffic.containsKey(event.getVehicleId())) {
				Id<Person> personId = vehicle2DriverInTraffic.get(event.getVehicleId());

				dataMap.put(personId, dataMap.get(personId).updateDailyDistance(network.getLinks().get(event.getLinkId()).getLength()));
			}
		}

		@Override
		public void handleEvent(PersonMoneyEvent event) {
//			event structure:
//			<event time="31181.0" type="personMoney" person="hoyerswerdaOnly" dailyCost="-3.0" purposes="pt fare" transactionPartner="VVO Tarifzone 20" reference="hoyerswerdaOnly"  />

			if (event.getPurpose().contains("fare")) {
//				initialize data element if not in map
				dataMap.putIfAbsent(event.getPersonId(),
					new SimulationData(event.getPersonId(), 0., new ArrayList<>(), new ArrayList<>(), 0., 0.));

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
//			car. no other mode has monetary utility components in the lausitz scenario.
			if (event.getVehicleId().toString().contains(TransportMode.car) &&
				!(event.getVehicleId().toString().contains("goods") || event.getVehicleId().toString().contains("commercial") || event.getVehicleId().toString().contains("freight"))) {
				dataMap.putIfAbsent(event.getPersonId(), new SimulationData(event.getPersonId(), 0., new ArrayList<>(), new ArrayList<>(), 0., 0.));
				vehicle2DriverInTraffic.put(event.getVehicleId(), event.getPersonId());
			}
		}

		@Override
		public void handleEvent(VehicleLeavesTrafficEvent event) {
//			last link has no LinkLeaveEvent. register the distance here.
			if (vehicle2DriverInTraffic.containsKey(event.getVehicleId())) {
				Id<Person> personId = event.getPersonId();

				dataMap.put(personId, dataMap.get(personId).updateDailyDistance(network.getLinks().get(event.getLinkId()).getLength()));
				vehicle2DriverInTraffic.remove(event.getVehicleId());
			}
		}
	}

	private record SimulationData(Id<Person> personId, double dailyCost, List<String> purposes, List<String> fareTypes, double dailyRefund, double dailyDistance) {
		private SimulationData updateDailyCost(double amount) {
			return new SimulationData(this.personId, this.dailyCost + amount, this.purposes, this.fareTypes, this.dailyRefund, this.dailyDistance);
		}
		private SimulationData updateDailyRefund(double refund) {
			return new SimulationData(this.personId, this.dailyCost, this.purposes, this.fareTypes, this.dailyRefund + refund, this.dailyDistance);
		}
		private SimulationData updateDailyDistance(double distance) {
			return new SimulationData(this.personId, this.dailyCost, this.purposes, this.fareTypes, this.dailyRefund, this.dailyDistance + distance);
		}
		private SimulationData updatePurposeList(String purpose) {
			List<String> updatedPurposes = new ArrayList<>(this.purposes);
			updatedPurposes.add(purpose);
			return new SimulationData(this.personId, this.dailyCost, updatedPurposes, this.fareTypes, this.dailyRefund, this.dailyDistance);
		}
		private SimulationData updateFareTypesList(String type) {
			List<String> updatedFareTypes = new ArrayList<>(this.fareTypes);
			updatedFareTypes.add(type);
			return new SimulationData(this.personId, this.dailyCost, this.purposes, updatedFareTypes, this.dailyRefund, this.dailyDistance);
		}
	}
}
