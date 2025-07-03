package org.matsim.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.run.scenarios.*;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;

class DrtAndPtFareTest {
	Logger log = LogManager.getLogger(DrtAndPtFareTest.class);

	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@TempDir
	private Path p;

	private String inputPath;

	private final static Id<Person> hoyerswerdaOnly = Id.createPersonId("hoyerswerdaOnly");
	private final static Id<Person> hoyerswerdaCottbus = Id.createPersonId("hoyerswerdaCottbus");

	private final static Double delta = 2.0;

	@BeforeEach
	void setUp() {
		// Initialize inputPath after p is injected
		inputPath = p.resolve("test-population.xml.gz").toString();
	}

	@Test
	void testPtAndDrtFare() throws IOException {

		Map<String, Map<Id<Person>, Double>> fares = new HashMap<>();

		for (String mode : Set.of(TransportMode.pt, TransportMode.drt)) {
			Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
			ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

			String outputDir = utils.getOutputDirectory() + mode + "/";

			switch (mode) {
				case TransportMode.pt:
					createTestPopulation(config, List.of(TransportMode.pt, TransportMode.pt));

					assert MATSimApplication.execute(LausitzScenario.class, config,
						"--1pct",
						"--iterations", "0",
						"--output", outputDir,
						"--config:plans.inputPlansFile", inputPath,
						"--config:controller.overwriteFiles=deleteDirectoryIfExists",
						"--config:global.numberOfThreads", "2",
						"--config:qsim.numberOfThreads", "2",
						"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS")
						== 0 : "Must return non error code";
					break;

				case TransportMode.drt:
					createTestPopulation(config, List.of(TransportMode.pt, TransportMode.drt));

					assert MATSimApplication.execute(LausitzDrtScenario.class, config,
						"--1pct",
						"--iterations", "0",
						"--config:plans.inputPlansFile", inputPath,
						"--output", outputDir,
						"--config:controller.overwriteFiles=deleteDirectoryIfExists",
						"--config:global.numberOfThreads", "2",
						"--config:qsim.numberOfThreads", "2",
						"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS")
						== 0 : "Must return non error code";
					break;

				default: throw new IllegalArgumentException();
			}
			Assertions.assertTrue(new File(outputDir).isDirectory());
			Assertions.assertTrue(new File(outputDir).exists());

			String personMoneyEventsPath = globFile(Path.of(outputDir), "*output_personMoneyEvents.tsv.gz").toString();

			Table moneyEvents = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(personMoneyEventsPath))
				.columnTypesPartial(Map.of("time", ColumnType.DOUBLE, "person", ColumnType.TEXT, "amount", ColumnType.DOUBLE, "purpose", ColumnType.TEXT, "transactionPartner", ColumnType.TEXT))
				.sample(false)
				.separator(CsvOptions.detectDelimiter(personMoneyEventsPath)).build());

			Table hoyerswerdaOnlyTable = moneyEvents.where(moneyEvents.textColumn("person").containsString(hoyerswerdaOnly.toString()));
			Table hoyerswerdaCottbusTable = moneyEvents.where(moneyEvents.textColumn("person").containsString(hoyerswerdaCottbus.toString()));

			double fareHoy = hoyerswerdaOnlyTable.doubleColumn("amount").sum();
			double fareHoyCott = hoyerswerdaCottbusTable.doubleColumn("amount").sum();

			Map<Id<Person>, Double> personsToFares = new HashMap<>();
			personsToFares.put(hoyerswerdaOnly, fareHoy);
			personsToFares.put(hoyerswerdaCottbus, fareHoyCott);

			fares.put(mode, personsToFares);
		}


//		assert that pt and drt fare are in the same range for 1) agent in vvo area and 2) agent to cott (distance based pt fare)
		Assertions.assertEquals(fares.get(TransportMode.pt).get(hoyerswerdaOnly), fares.get(TransportMode.drt).get(hoyerswerdaOnly));
		Assertions.assertEquals(fares.get(TransportMode.pt).get(hoyerswerdaCottbus), fares.get(TransportMode.drt).get(hoyerswerdaCottbus), delta);
	}

	private void createTestPopulation(Config config, List<String> modes) {
		Population population = PopulationUtils.createPopulation(config);
		PopulationFactory fac = population.getFactory();
		Person hoyCott = fac.createPerson(hoyerswerdaCottbus);
		hoyCott.getAttributes().putAttribute("home_x", 863949.91);
		hoyCott.getAttributes().putAttribute("home_y", 5711547.75);
		Plan plan = PopulationUtils.createPlan(hoyCott);

//		home in hoyerswerda
		Activity home = fac.createActivityFromCoord("home_2400", new Coord(863949.91, 5711547.75));
		home.setEndTime(23788.);
//		work in cottbus
		Activity work = fac.createActivityFromCoord("work_2400", new Coord(867489.48,5746587.47));
		work.setEndTime(17 * 3600 + 25 * 60);

		Leg drtLeg = fac.createLeg(modes.get(1));
		drtLeg.setRoutingMode(TransportMode.pt);

		plan.addActivity(home);
		plan.addLeg(drtLeg);
		plan.addActivity(work);

		hoyCott.addPlan(plan);
		PersonUtils.setIncome(hoyCott, 1000.);
		PersonUtils.setAge(hoyCott, 30);
		hoyCott.getAttributes().putAttribute("subpopulation", "person");
		population.addPerson(hoyCott);

		Person hoyOnly = fac.createPerson(hoyerswerdaOnly);
		hoyOnly.getAttributes().putAttribute("home_x", 863434.54);
		hoyOnly.getAttributes().putAttribute("home_y", 5712142.21);
		Plan plan2 = PopulationUtils.createPlan(hoyOnly);

//		home in hoyerswerda
		Activity home2 = fac.createActivityFromCoord("home_2400", new Coord(863434.54,5712142.21));
		home2.setEndTime(8 * 3600);
//		work in hoyerswerda
		Activity work2 = fac.createActivityFromCoord("work_2400", new Coord(865722.89,5711744.30));
		work2.setEndTime(17 * 3600 + 25 * 60);

		plan2.addActivity(home2);
		plan2.addLeg(fac.createLeg(modes.get(1)));
		plan2.addActivity(work2);

		hoyOnly.addPlan(plan2);
		PersonUtils.setIncome(hoyOnly, 1000.);
		PersonUtils.setAge(hoyOnly, 30);
		hoyOnly.getAttributes().putAttribute("subpopulation", "person");
		population.addPerson(hoyOnly);

		new PopulationWriter(population).write(this.inputPath);
	}
}
