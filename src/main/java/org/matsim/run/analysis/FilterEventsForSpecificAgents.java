package org.matsim.run.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.HasPersonId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(
	name = "filter-events",
	description = "Helper class for filtering agents based on a given list/file of agentIds. The agentIds should be contained by the first column of the file."
)
public class FilterEventsForSpecificAgents implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(FilterEventsForSpecificAgents.class);

	@CommandLine.Option(names = "--agents", description = "Path to csv file with agentIds for filtering. AgentIds should be contained by the first column of the file.", required = true)
	private String agentsPath;
	@CommandLine.Option(names = "--run-dir", description = "Path to directory with run output.", required = true)
	private Path runDir;

	public static void main(String[] args) {
		new FilterEventsForSpecificAgents().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		String eventsFile = globFile(runDir, "*output_events.xml.gz").toString();

		Set<Id<Person>> agentSet = new HashSet<>();

//		read csv file with agentIds
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

		filterAndWriteEvents(eventsFile, agentSet, new ArrayList<>());

		return 0;
	}

	private void filterAndWriteEvents(String eventsFile, Set<Id<Person>> agentSet, List<Event> filteredEvents) throws IOException {
		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new PersonFilterEventsHandler(agentSet, filteredEvents));
		manager.initProcessing();

		MatsimEventsReader reader = new MatsimEventsReader(manager);
		reader.readFile(eventsFile);
		manager.finishProcessing();

		//		for writing filtered events out of list
		BufferedWriter eventsWriter = IOUtils.getBufferedWriter((runDir.endsWith("/")) ? runDir + "output_events_filtered.xml.gz" : runDir + "/" + "output_events_filtered.xml.gz");
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
	}

	private final class PersonFilterEventsHandler implements BasicEventHandler {
		Set<Id<Person>> agentSet;
		List<Event> filteredEvents;
		private PersonFilterEventsHandler(Set<Id<Person>> agentSet, List<Event> filteredEvents) {
			this.agentSet = agentSet;
			this.filteredEvents = filteredEvents;
		}

		@Override
		public void handleEvent(Event event) {
			if (event instanceof HasPersonId hasPersonId && agentSet.contains(hasPersonId.getPersonId())) {
				filteredEvents.add(event);
			}
		}
	}
}
