package org.matsim.run.drtPostSimulation;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.Attributes;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter.glob;

/**
 * Extract DRT trips from a MATSim run output folder.
 */
@CommandLine.Command(name = "extract-drt-trips", description = "Extract drt trips from output runs")
public class ExtractDrtTrips implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(ExtractDrtTrips.class);

	@CommandLine.Option(names = "--run-folder", description = "path to the output run folder which is taken as input for drt plans creation.", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Output DRT plans", required = true)
	private Path output;

	public static void main(String[] args) {
		new ExtractDrtTrips().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		if (!Files.exists(output.getParent())) {
			Files.createDirectories(output.getParent());
		}

		Path outputDrtLegsFile = glob(input, "*output_drt_legs_drt.csv").orElse(Path.of("no such file"));
		String outputPlansFile = glob(input, "*output_plans.xml.gz").orElse(Path.of("no such file")).toString();
		Population outputPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		PopulationFactory populationFactory = outputPlans.getFactory();

		Population fullPop = PopulationUtils.readPopulation(outputPlansFile);

		try (CSVParser parser = new CSVParser(Files.newBufferedReader(outputDrtLegsFile),
			CSVFormat.Builder.create()
				.setDelimiter(CsvOptions.detectDelimiter(outputDrtLegsFile.toString()))
				.setHeader().setSkipHeaderRecord(true)
				.build())) {
			int counter = 0;
			for (CSVRecord rec : parser.getRecords()) {
				Id<Person> personId = Id.createPersonId(rec.get("personId"));
				Coord fromCoord = new Coord(Double.parseDouble(rec.get("fromX")), Double.parseDouble(rec.get("fromY")));
				Coord toCoord = new Coord(Double.parseDouble(rec.get("toX")), Double.parseDouble(rec.get("toY")));
				double submissionTime = Double.parseDouble(rec.get("submissionTime"));

				Person person = populationFactory.createPerson(Id.createPersonId("drt-passenger-" + counter));

//				retrieve person attributes from full output population and add to new drt person
				Attributes attrs = fullPop.getPersons().get(personId).getAttributes();
//				vehicles attr is generated automatically
				attrs.removeAttribute("vehicles");
				if (!attrs.isEmpty()) {
					attrs.getAsMap().keySet().forEach(a -> person.getAttributes().putAttribute(a, attrs.getAttribute(a)));
				} else {
					log.warn("Could not find attributes for person {} in output population of run directory {}. " +
						"Please check if the person was created correctly.", personId, input);
				}

				Plan plan = populationFactory.createPlan();
				Activity fromAct = populationFactory.createActivityFromCoord("dummy", fromCoord);
				fromAct.setEndTime(submissionTime);
				plan.addActivity(fromAct);
				Leg leg = populationFactory.createLeg(TransportMode.drt);
				plan.addLeg(leg);
				Activity toAct = populationFactory.createActivityFromCoord("dummy", toCoord);
				plan.addActivity(toAct);
				person.addPlan(plan);
				outputPlans.addPerson(person);

				counter++;
			}
		}

		// write out DRT plans
		new PopulationWriter(outputPlans).write(output.toString());

		return 0;
	}
}
