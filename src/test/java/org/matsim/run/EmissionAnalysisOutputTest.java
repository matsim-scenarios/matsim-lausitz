package org.matsim.run;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.matsim.application.ApplicationUtils.globFile;

class EmissionAnalysisOutputTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@TempDir
	public Path p;

	private final static Id<Person> ptPersonId = Id.createPersonId("Hoyerswerda-Cottbus_CAR");
	@Disabled("Test is used to secure functionality of emission analysis. As the analysis needs" +
		"a lot of RAM, it is disabled and only run manually. -sme0924")
	@Test
	void runEmissionAnalysisOutputTest() throws IOException {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
//		Here, we do want dashboards in general (emission dashboard), but do not want to waste computation time on the standard dashboards
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).exclude = Set.of("TripDashboard", "OverviewDashboard", "StuckAgentDashboard",
			"TrafficCountsDashboard", "TrafficDashboard", "PublicTransitDashboard");

		Path inputPath = p.resolve("emissions-test-population.xml.gz");

		Population population = PopulationUtils.createPopulation(config);
		PopulationFactory fac = population.getFactory();
		Person person = fac.createPerson(ptPersonId);
		Plan plan = PopulationUtils.createPlan(person);

//		home in hoyerswerda, nearest link 28922425#0
		Activity home = fac.createActivityFromCoord("home_2400", new Coord(863538.13,5711028.24));
		home.setEndTime(8 * 3600);
		Activity home2 = fac.createActivityFromCoord("home_2400", new Coord(863538.13,5711028.24));
		home2.setEndTime(19 * 3600);
//		work in hoyerswerda, nearest link(s): 686055693#1, -686055693#1
		Activity work = fac.createActivityFromCoord("work_2400", new Coord(863866.47,5710961.86));
		work.setEndTime(17 * 3600 + 25 * 60);

		Leg leg = fac.createLeg(TransportMode.car);

		plan.addActivity(home);
		plan.addLeg(leg);
		plan.addActivity(work);
		plan.addLeg(leg);
		plan.addActivity(home2);

		person.addPlan(plan);
		PersonUtils.setIncome(person, 1000.);
		person.getAttributes().putAttribute("subpopulation", "person");
		population.addPerson(person);

		new PopulationWriter(population).write(inputPath.toString());

		assert MATSimApplication.execute(LausitzScenario.class, config,
			"--1pct",
			"--iterations", "0",
			"--output", utils.getOutputDirectory(),
			"--config:plans.inputPlansFile", inputPath.toString(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists") == 0 : "Must return non error code";


		Path csvPath = globFile(Path.of(utils.getOutputDirectory() + "/analysis/emissions"), "*emissions_per_link.csv*");

		Map<String, Double[]> nonZeroLinks = new HashMap<>();

		try {
			BufferedReader reader = IOUtils.getBufferedReader(csvPath.toUri().toURL());
			String line;

//			skip header
			reader.readLine();

			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");

//				if first value (CO) is zero, all others are too
				if (Double.parseDouble(parts[1]) == 0.) {
					continue;
				}

				Double[] values = new Double[23];

				for (int i = 1; i < parts.length; i++) {
					values[i - 1] = Double.parseDouble(parts[i]);
				}

				nonZeroLinks.put(parts[0], values);
			}
		} finally {

		}

		Assertions.assertFalse(nonZeroLinks.isEmpty());
		Assertions.assertTrue(nonZeroLinks.containsKey("28922425#0"));
		Assertions.assertTrue(nonZeroLinks.containsKey("-686055693#1"));
	}
}
