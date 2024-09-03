package org.matsim.run;

import com.univocity.parsers.common.input.EOFException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.matsim.application.ApplicationUtils.globFile;

class RunIntegrationTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@TempDir
	public Path p;

	private final static Id<Person> ptPersonId = Id.createPersonId("Hoyerswerda-Cottbus_PT");

	@Test
	void runScenario() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		assert MATSimApplication.execute(LausitzScenario.class, config,
			"--1pct",
			"--iterations", "1",
			"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/input/v1.1/lausitz-v1.1-1pct.plans-initial.xml.gz",
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists") == 0 : "Must return non error code";
	}

	@Test
	void runScenarioIncludingDrt() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		Path inputPath = p.resolve("drt-test-population.xml.gz");

		createTestPopulation(config, inputPath, TransportMode.drt, new Coord(838300.95,5711890.36));

		assert MATSimApplication.execute(RunLausitzDrtScenario.class, config,
			"--1pct",
			"--iterations", "1",
			"--config:plans.inputPlansFile", inputPath.toString(),
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists") == 0 : "Must return non error code";

		//read customer stats
		Path customerStatsPath = ApplicationUtils.matchInput("drt_customer_stats_" + TransportMode.drt + ".csv", Path.of(utils.getOutputDirectory()));
		try {
			Table customerStats = Table.read()
				.csv(CsvReadOptions.builder(customerStatsPath.toFile())
				.columnTypes(columnHeader -> columnHeader.equals("runId") ? ColumnType.STRING : ColumnType.DOUBLE)
				.separator(CsvOptions.detectDelimiter(customerStatsPath.toString()))
					.build());

			Assertions.assertEquals(2, customerStats.rowCount());

			DoubleColumn rideCol = customerStats.doubleColumn("rides");

//			there should be exactly 2 drt rides
			Assertions.assertEquals(2, rideCol.get(rideCol.size() - 1));
		} catch (IOException e) {
			throw new EOFException();
		}
	}

	@Test
	void runScenarioIncludingAdditionalPtLine() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		Path inputPath = p.resolve("pt-test-population.xml.gz");

		createTestPopulation(config, inputPath, TransportMode.pt, new Coord(867489.48,5746587.47));

		assert MATSimApplication.execute(RunLausitzPtScenario.class, config,
			"--1pct",
			"--iterations", "1",
			"--output", utils.getOutputDirectory(),
			"--config:plans.inputPlansFile", inputPath.toString(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists") == 0 : "Must return non error code";

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		new TransitScheduleReader(scenario).readFile(globFile(Path.of(utils.getOutputDirectory()), "*output_transitSchedule*").toString());
		TransitSchedule schedule = scenario.getTransitSchedule();

		Network network = NetworkUtils.readNetwork(globFile(Path.of(utils.getOutputDirectory()), "*output_network.*").toString());

		Assertions.assertTrue(schedule.getFacilities().containsKey(Id.create("regio_135278.2_0", TransitStopFacility.class)));
		Assertions.assertTrue(schedule.getTransitLines().containsKey(Id.create("RE-VSP1", TransitLine.class)));
		Assertions.assertEquals(2, schedule.getTransitLines().get(Id.create("RE-VSP1", TransitLine.class)).getRoutes().size());
		Assertions.assertEquals(20, schedule.getTransitLines().get(Id.create("RE-VSP1", TransitLine.class))
			.getRoutes().get(Id.create("RE-VSP1_0", TransitRoute.class)).getDepartures().size());
		Assertions.assertTrue(network.getLinks().containsKey(Id.createLinkId("pt_vsp_1")));
		Assertions.assertTrue(network.getNodes().containsKey(Id.createNodeId("pt_short_2476.2")));

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(new PersonEntersPtVehicleEventHandler());
		EventsUtils.readEvents(manager, globFile(Path.of(utils.getOutputDirectory()), "*output_events*").toString());

		Assertions.assertEquals(2, PersonEntersPtVehicleEventHandler.enterEvents.size());
//		to cottbus: train at 8:22, from cottbus train at 17:53
		Assertions.assertEquals("pt_RE-VSP1_0_9", PersonEntersPtVehicleEventHandler.enterEvents.get(0).getVehicleId().toString());
		Assertions.assertEquals("pt_RE-VSP1_1_18", PersonEntersPtVehicleEventHandler.enterEvents.get(1).getVehicleId().toString());

	}

	private static void createTestPopulation(Config config, Path inputPath, String mode, Coord workLocation) {
		Population population = PopulationUtils.createPopulation(config);
		PopulationFactory fac = population.getFactory();
		Person person = fac.createPerson(ptPersonId);
		Plan plan = PopulationUtils.createPlan(person);

//		home in hoyerswerda
		Activity home = fac.createActivityFromCoord("home_2400", new Coord(863538.13,5711028.24));
		home.setEndTime(8 * 3600);
		Activity home2 = fac.createActivityFromCoord("home_2400", new Coord(863538.13,5711028.24));
		home2.setEndTime(19 * 3600);
//		work in given location
		Activity work = fac.createActivityFromCoord("work_2400", workLocation);
		work.setEndTime(17 * 3600 + 25 * 60);

		Leg leg = fac.createLeg(mode);

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
	}

	private static final class PersonEntersPtVehicleEventHandler implements PersonEntersVehicleEventHandler {
		static List<PersonEntersVehicleEvent> enterEvents = new ArrayList<>();

		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			if (event.getPersonId().equals(ptPersonId) && event.getVehicleId().toString().contains("RE-VSP1")) {
				enterEvents.add(event);
			}
		}
	}
}
