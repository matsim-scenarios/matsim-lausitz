package org.matsim.run.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.run.scenarios.LausitzScenario.SLASH;

@CommandLine.Command(
	name = "filter-events",
	description = "Helper class for filtering agents based on a given list/file of agentIds. The agentIds should be contained by the first column of the file."
)
public class FilterEventsForSpecificAgents implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(FilterEventsForSpecificAgents.class);

	@CommandLine.Option(names = "--agents", description = "Path to csv file with agentIds for filtering. AgentIds should be contained by the first column of the file.", required = true)
	private Path agentsPath;
	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which dashboards are to be generated.")
	private List<Path> inputPaths;
	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional.", defaultValue = "")
	private String prefix;

	public static void main(String[] args) {
		new FilterEventsForSpecificAgents().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		for (Path runDir : inputPaths) {
			log.info("Running on {}", runDir);

			String eventsFile = globFile(runDir, "*output_events.xml.gz").toString();

//			get absolute path out of potentially relative agentsPath.
//			if agentsPath is absolute, it will be taken as is, see path.resolve().
			Path absoluteAgentsPath = runDir.resolve(agentsPath).normalize();

			//		read csv file with agentIds
			Set<Id<Person>> agentSet = readPersonsCsv(absoluteAgentsPath.toString());

			filterAndWriteEvents(eventsFile, agentSet, new ArrayList<>(), runDir.toString());
		}

		return 0;
	}

	/**
	 * Utils method to read a csv file containing personIds.
	 */
	public static Set<Id<Person>> readPersonsCsv(String agentsPath) throws IOException {
		Set<Id<Person>> agentSet = new HashSet<>();

		try(BufferedReader br = new BufferedReader(new FileReader(agentsPath))) {
			String line;
			String delim = String.valueOf(CsvOptions.detectDelimiter(agentsPath));
			while ((line = br.readLine()) != null) {
//				csv header: person,time; where time is the EntersPtVehicleTime for the new pt line
				String[] parts = line.split(delim, 2);
				if (parts.length >= 2) {
					agentSet.add(Id.createPersonId(parts[0]));
				} else {
					log.warn("Skipping malformed line: {}", line);
				}
			}
		}
		return agentSet;
	}

	private void filterAndWriteEvents(String eventsFile, Set<Id<Person>> agentSet, List<Event> filteredEvents, String runDir) throws IOException {
		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new PersonFilterEventsHandler(agentSet, filteredEvents));
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsFile);
		manager.finishProcessing();

		//		for writing filtered events out of list
		String outPath = (runDir.endsWith(SLASH)) ? runDir + prefix + "output_events_filtered.xml.gz" : runDir + SLASH + prefix + "output_events_filtered.xml.gz";

		BufferedWriter eventsWriter = IOUtils.getBufferedWriter(outPath);
		eventsWriter.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
		eventsWriter.newLine();
		eventsWriter.write("<events version=\"1.0\">");
		for (Event event : filteredEvents) {
			eventsWriter.newLine();
			eventsWriter.write(event.toString());
		}
		eventsWriter.newLine();
		eventsWriter.write("</events>");
		eventsWriter.close();
		log.info("Filtered events written to {}", outPath);
	}

	private static final class PersonFilterEventsHandler implements BasicEventHandler, LinkEnterEventHandler, LinkLeaveEventHandler {
		Set<Id<Person>> agentSet;
		List<Event> filteredEvents;
		Map<Id<Vehicle>, Set<Id<Person>>> person2Vehicle = new HashMap<>();
		private PersonFilterEventsHandler(Set<Id<Person>> agentSet, List<Event> filteredEvents) {
			this.agentSet = agentSet;
			this.filteredEvents = filteredEvents;
		}

		@Override
		public void handleEvent(Event event) {
			if (event instanceof HasPersonId hasPersonId && agentSet.contains(hasPersonId.getPersonId())) {
				filteredEvents.add(event);

				if (event instanceof PersonEntersVehicleEvent personEntersVehicleEvent) {
//					we want to track vehicles which our agents enter
					person2Vehicle.putIfAbsent(personEntersVehicleEvent.getVehicleId(), new HashSet<>());
					person2Vehicle.get(personEntersVehicleEvent.getVehicleId()).add(personEntersVehicleEvent.getPersonId());
				}

				if (event instanceof PersonLeavesVehicleEvent personLeavesVehicleEvent && this.person2Vehicle.containsKey(personLeavesVehicleEvent.getVehicleId())) {
					person2Vehicle.get(personLeavesVehicleEvent.getVehicleId()).remove(personLeavesVehicleEvent.getPersonId());
//					remove vehicle from map if person set is empty
					if (person2Vehicle.get(personLeavesVehicleEvent.getVehicleId()).isEmpty()) {
						person2Vehicle.remove(personLeavesVehicleEvent.getVehicleId());
					}
				}
			}
		}

		@Override
		public void handleEvent(LinkEnterEvent event) {
//			add event if one of our filtered agents is in the vehicle
			if (person2Vehicle.containsKey(event.getVehicleId())) {
				filteredEvents.add(event);
			}
		}

		@Override
		public void handleEvent(LinkLeaveEvent event) {
			if (person2Vehicle.containsKey(event.getVehicleId())) {
				filteredEvents.add(event);
			}
		}
	}
}
