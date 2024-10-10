package org.matsim.run.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.handler.AgentWaitingForPtEventHandler;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(
	name = "pt-line",
	description = "Get all agents who use the newly created pt line."
)
public class PtLineAnalysis implements MATSimAppCommand {

	@CommandLine.Option(names = "--dir", description = "Run directory with necessary data.", required = true)
	private Path dir;

	@CommandLine.Option(names = "--output", description = "Output path", required = true)
	private String outputPath;

	private final Set<Id<Person>> ptPersons = new HashSet<>();
	private final Map<Id<Person>, List<EventData>> eventMap = new HashMap<>();

	public static void main(String[] args) {
		new PtLineAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		String eventsFile = globFile(dir, "*output_events.xml.gz").toString();
		String transitScheduleFile = globFile(dir, "*output_transitSchedule.xml.gz").toString();

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		TransitScheduleReader transitScheduleReader = new TransitScheduleReader(scenario);
		transitScheduleReader.readFile(transitScheduleFile);

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new NewPtLineEventHandler(scenario.getTransitSchedule()));
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsFile);
		manager.finishProcessing();

//		only keep agents which used new pt line
		Map<Id<Person>, List<EventData>> relevantEvents = eventMap.entrySet().stream()
			.filter(entry -> ptPersons.contains(entry.getKey()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Path.of(outputPath)), CSVFormat.DEFAULT)) {
			printer.printRecord("person", "eventType", "time", "x", "y");
			for (Map.Entry<Id<Person>, List<EventData>> e : relevantEvents.entrySet()) {
				for (EventData eventData : e.getValue()) {
					printer.printRecord(e.getKey().toString(), eventData.eventType, eventData.time, eventData.coord.getX(), eventData.coord.getY());
				}
			}
		}
		return 0;
	}


	private final class NewPtLineEventHandler implements PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler, AgentWaitingForPtEventHandler {
		TransitSchedule schedule;
		NewPtLineEventHandler(TransitSchedule schedule) {
			this.schedule = schedule;
		}
		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			if (event.getVehicleId().toString().contains("RE-VSP1") && !event.getPersonId().toString().contains("pt_")) {
				eventMap.get(event.getPersonId()).add(new EventData(event.getEventType(), event.getTime(), new Coord(0, 0)));
				ptPersons.add(event.getPersonId());
			}
		}

		@Override
		public void handleEvent(AgentWaitingForPtEvent event) {
			if (!event.getPersonId().toString().contains("pt_")) {
				if (!eventMap.containsKey(event.getPersonId())) {
					eventMap.put(event.getPersonId(), new ArrayList<>());
				}
				eventMap.get(event.getPersonId())
					.add(new EventData(event.getEventType(), event.getTime(), new Coord(
						schedule.getFacilities().get(event.waitingAtStopId).getCoord().getX(),
						schedule.getFacilities().get(event.waitingAtStopId).getCoord().getY())));
			}

		}

		@Override
		public void handleEvent(PersonLeavesVehicleEvent event) {
			if (ptPersons.contains(event.getPersonId())) {
				eventMap.get(event.getPersonId()).add(new EventData(event.getEventType(), event.getTime(), new Coord(0, 0)));
			}
		}
	}

	private record EventData(String eventType, double time, Coord coord) {}
}
