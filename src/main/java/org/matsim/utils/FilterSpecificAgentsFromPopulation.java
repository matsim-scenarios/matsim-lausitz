package org.matsim.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(
	name = "filter-agents",
	description = "Helper class for filtering agents out of a population based on a given list/file of agentIds. The agentIds should be contained by the first column of the file."
)
public class FilterSpecificAgentsFromPopulation implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(FilterSpecificAgentsFromPopulation.class);

	@CommandLine.Option(names = "--agents", description = "Path to csv file with agentIds for filtering. AgentIds should be contained by the first column of the file.", required = true)
	private String agentsPath;
	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which dashboards are to be generated.")
	private List<Path> inputPaths;
	@CommandLine.Option(names = "--suffix", description = "Suffix for filtered plans output file, optional.", defaultValue = "")
	private String suffix;

	public static void main(String[] args) {
		new FilterSpecificAgentsFromPopulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		//		read csv file with agentIds
		Set<Id<Person>> agentSet = FilterEventsForSpecificAgents.readPersonsCsv(agentsPath);

		for (Path runDir : inputPaths) {
			log.info("Running on {}", runDir);

			String populationFile = globFile(runDir, "*output_plans.xml.gz").toString();
			Population population = PopulationUtils.readPopulation(populationFile);

			Set<Person> filteredPersons = new HashSet<>();

			population.getPersons().values().stream()
				.filter(p -> !agentSet.contains(p.getId()))
				.forEach(filteredPersons::add);

			filteredPersons.forEach(p -> population.removePerson(p.getId()));

			PopulationUtils.writePopulation(population, populationFile.split(".xml.gz")[0] + "_" + suffix + "_filtered.xml.gz");
		}
		return 0;
	}
}
