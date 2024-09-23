package org.matsim.run.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@CommandLine.Command(
	name = "pt-line",
	description = "Get all agents who use the newly created pt line."
)
public class PtLineAnalysis implements MATSimAppCommand {

	@CommandLine.Option(names = "--events", description = "Events to be analyzed.", required = true)
	private String eventsFile;

	@CommandLine.Option(names = "--output", description = "Output path", required = true)
	private String outputPath;

	Map<Id<Person>, Double> ptPersons = new HashMap<>();

	public static void main(String[] args) {
		new PtLineAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new PersonEntersPtVehicleEventHandler());
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsFile);
		manager.finishProcessing();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Path.of(outputPath)), CSVFormat.DEFAULT)) {
			printer.printRecord("person", "time");
			for (Map.Entry<Id<Person>, Double> e : ptPersons.entrySet()) {
				printer.printRecord(e.getKey().toString(), e.getValue());
			}
		}

		return 0;
	}


	private final class PersonEntersPtVehicleEventHandler implements PersonEntersVehicleEventHandler {
		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			if (event.getVehicleId().toString().contains("RE-VSP1")) {
				if (!event.getPersonId().toString().contains("pt_")) {
					ptPersons.put(event.getPersonId(), event.getTime());
				}
			}
		}
	}
}
