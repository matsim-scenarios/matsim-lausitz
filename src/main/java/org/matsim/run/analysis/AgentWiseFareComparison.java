package org.matsim.run.analysis;

import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.run.analysis.PtLineAnalysis.getCsvFormat;

@CommandLine.Command(name = "fare", description = "List and compare fare values for agents in base and policy case.")
public class AgentWiseFareComparison implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AgentWiseFareComparison.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which analysis should be performed.")
	private List<Path> inputPaths;
	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path basePath;
	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional.", defaultValue = "")
	private String prefix;

	private static final String POLICY = "policy";
	private static final String BASE = "base";

	public static void main(String[] args) {
		new AgentWiseFareComparison().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		String pattern = "*" + prefix + "output_events.xml.gz";
		String baseEventsFile = globFile(basePath, pattern).toString();

		//			read base case events
		Map<Id<Person>, FareData> baseFareDataMap = new HashMap<>();
		FareEventHandler baseHandler = new FareEventHandler(baseFareDataMap);

		EventsManager baseManager = EventsUtils.createEventsManager();
		baseManager.addHandler(baseHandler);
		baseManager.initProcessing();

		MatsimEventsReader baseReader = new MatsimEventsReader(baseManager);
		baseReader.readFile(baseEventsFile);
		baseManager.finishProcessing();

		for(Path inputPath : inputPaths) {
			log.info("Running on {}", inputPath);
//			read policy case events
			String eventsFile = globFile(inputPath, pattern).toString();
			Map<Id<Person>, FareData> policyFareDataMap = new HashMap<>();

			EventsManager manager = EventsUtils.createEventsManager();
			manager.addHandler(new FareEventHandler(policyFareDataMap));
			MatsimEventsReader policyReader = new MatsimEventsReader(manager);
			policyReader.readFile(eventsFile);
			manager.finishProcessing();

//			bring base and policy maps together
			Map<Id<Person>, Map<String, FareData>> combinedFareData = new HashMap<>();

			for (Map.Entry<Id<Person>, FareData> entry : baseFareDataMap.entrySet()) {
				combinedFareData.put(entry.getKey(), new HashMap<>());
				combinedFareData.get(entry.getKey()).put(BASE, entry.getValue());
			}

			for (Map.Entry<Id<Person>, FareData> entry : policyFareDataMap.entrySet()) {
//				if combined map does not contain policy person, the person did not use pt in base case
//				thus, we add base case agent with null values
				if (!combinedFareData.containsKey(entry.getKey())) {
					combinedFareData.put(entry.getKey(), new HashMap<>());
					combinedFareData.get(entry.getKey()).put(BASE, new FareData(entry.getKey(), 0., null, null, 0.));
				}
				combinedFareData.get(entry.getKey()).put(POLICY, entry.getValue());
			}

//			the remaining fare data maps in combinedFareData with 1 entry only are agents who used pt in base case
//			but no pt/drt in policy. we add null values for them in policy case
			for (Map.Entry<Id<Person>, Map<String, FareData>> entry : combinedFareData.entrySet()) {
				if (entry.getValue().size() == 1) {
					combinedFareData.get(entry.getKey()).put(POLICY, new FareData(entry.getKey(), 0., null, null, 0.));
				} else if (entry.getValue().isEmpty() || entry.getValue().size() > 2) {
					log.fatal("Size of fare data element map should be 1 or 2 but is {}! Please check your data.", entry.getValue().size());
					return 2;
				}
			}

			String output = inputPath.resolve("output_agent_wise_fare_comparison_to_base.tsv").toString();

			try (CSVPrinter printer = new CSVPrinter(new FileWriter(output), getCsvFormat())) {
				printer.printRecord("personId",
					"fareBase", "farePolicy", "fareDelta",
					"refundBase", "refundPolicy", "refundDelta",
					"totalBase", "totalPolicy", "totalDelta",
					"purposeBase", "purposePolicy",
					"typeBase", "typePolicy");

				for (Map.Entry<Id<Person>, Map<String, FareData>> entry : combinedFareData.entrySet()) {
					FareData baseFareData = entry.getValue().get(BASE);
					FareData policyFareData = entry.getValue().get(POLICY);
					double totalFareBase = baseFareData.amount + baseFareData.refund;
					double totalFarePolicy = policyFareData.amount + policyFareData.refund;

					printer.printRecord(entry.getKey().toString(),
						baseFareData.amount, policyFareData.amount, policyFareData.amount - baseFareData.amount,
						baseFareData.refund, policyFareData.refund, policyFareData.refund - baseFareData.refund,
						totalFareBase, totalFarePolicy, totalFarePolicy - totalFareBase,
						baseFareData.purpose, policyFareData.purpose,
						baseFareData.fareType, policyFareData.fareType);
				}
			}
		}
		return 0;
	}

	private static final class FareEventHandler implements PersonMoneyEventHandler {
		private final Map<Id<Person>, FareData> fareDataMap;

		FareEventHandler(Map<Id<Person>, FareData> fareDataMap) {
			this.fareDataMap = fareDataMap;
		}

		@Override
		public void handleEvent(PersonMoneyEvent event) {
//			event structure:
//			<event time="31181.0" type="personMoney" person="hoyerswerdaOnly" amount="-3.0" purpose="pt fare" transactionPartner="VVO Tarifzone 20" reference="hoyerswerdaOnly"  />

			if (event.getEventType().equals(PersonMoneyEvent.EVENT_TYPE) &&
			event.getPurpose().contains("fare")) {
//				initialize data element if not in map
				fareDataMap.putIfAbsent(event.getPersonId(),
					new FareData(event.getPersonId(), 0., event.getPurpose(), event.getTransactionPartner(), 0.));

				if (!event.getPurpose().contains("refund")) {
//					if not refund = we are handling a fare
					fareDataMap.put(event.getPersonId(), fareDataMap.get(event.getPersonId()).updateAmount(event.getAmount()));
				} else {
//					refund
					fareDataMap.put(event.getPersonId(), fareDataMap.get(event.getPersonId()).updateRefund(event.getAmount()));
				}
			}

		}
	}

	private record FareData(Id<Person> personId, double amount, String purpose, String fareType, double refund) {
		private FareData updateAmount(double amount) {
			return new FareData(this.personId, this.amount + amount, this.purpose, this.fareType, this.refund);
		}

		private FareData updateRefund(double refund) {
			return new FareData(this.personId, this.amount, this.purpose, this.fareType, this.refund + refund);
		}
	}
}
