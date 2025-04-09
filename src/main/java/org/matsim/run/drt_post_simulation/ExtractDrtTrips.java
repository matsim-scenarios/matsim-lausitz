package org.matsim.run.drt_post_simulation;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter.glob;

/**
 * Extract DRT trips from the main output folder.
 */
@CommandLine.Command(name = "extract-drt-trips", description = "Extract drt trips from output runs")
public class ExtractDrtTrips implements MATSimAppCommand {
	@CommandLine.Option(names = "--run-folder", description = "path to the output run folder", required = true)
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
		Population outputPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		PopulationFactory populationFactory = outputPlans.getFactory();

		try (CSVParser parser = new CSVParser(Files.newBufferedReader(outputDrtLegsFile),
			CSVFormat.Builder.create()
				.setDelimiter(CsvOptions.detectDelimiter(outputDrtLegsFile.toString()))
				.setHeader().setSkipHeaderRecord(true)
				.build())) {
			int counter = 0;
			for (CSVRecord record : parser.getRecords()) {
//				String personId = record.get("personId");
				Coord fromCoord = new Coord(Double.parseDouble(record.get("fromX")), Double.parseDouble(record.get("fromY")));
				Coord toCoord = new Coord(Double.parseDouble(record.get("toX")), Double.parseDouble(record.get("toY")));
				double submissionTime = Double.parseDouble(record.get("submissionTime"));

				Person person = populationFactory.createPerson(Id.createPersonId("drt-passenger-" + counter));
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
