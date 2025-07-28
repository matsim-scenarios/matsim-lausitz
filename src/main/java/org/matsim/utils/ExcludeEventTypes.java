package org.matsim.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.events.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.run.scenarios.LausitzScenario.SLASH;

@CommandLine.Command(
	name = "exclude-events",
	description = "Helper class for filtering agents based on a given list/file of event types." +
		"The event types in the list will be ignored for writing the filtered events file to make events reading faster."
)
public class ExcludeEventTypes implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(ExcludeEventTypes.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories.")
	private List<Path> inputPaths;
	@CommandLine.Option(names = "--event-types", split = ",", description = "Comma-separated list of event types to filter out.")
	Set<String> types;
	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional.", defaultValue = "types_")
	private String prefix;

	public static void main(String[] args) {
		new ExcludeEventTypes().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		for (Path runDir : inputPaths) {
			log.info("Running on {}", runDir);

			String eventsFile = globFile(runDir, "*output_events.xml.gz").toString();

			List<Event> filteredEvents = new ArrayList<>();

			EventsManager manager = EventsUtils.createEventsManager();
			manager.addHandler(new FilterEventsHandler(types, filteredEvents));
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
		return 0;
	}

	private static final class FilterEventsHandler implements BasicEventHandler {
		Set<String> types;
		List<Event> filteredEvents;

		private FilterEventsHandler(Set<String> types, List<Event> filteredEvents) {
			this.types = types;
			this.filteredEvents = filteredEvents;
		}

		@Override
		public void handleEvent(Event event) {
			if (!types.contains(event.getEventType())) {
				filteredEvents.add(event);
			}
		}
	}
}
