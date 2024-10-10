package org.matsim.run;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import org.matsim.application.MATSimApplication;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.run.scenarios.*;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.matsim.application.ApplicationUtils.globFile;

class RunIntegrationTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@TempDir
	private Path p;

	private String inputPath;

	private final static Id<Person> personId = Id.createPersonId("Hoyerswerda-Cottbus");

	private final RoutingModeMainModeIdentifier identifier = new RoutingModeMainModeIdentifier();

	@BeforeEach
	void setUp() {
		// Initialize inputPath after p is injected
		inputPath = p.resolve("test-population.xml.gz").toString();
	}

	@Test
	void runScenario() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		assert MATSimApplication.execute(LausitzScenario.class, config,
			"--1pct",
			"--iterations", "1",
			"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/input/v2024.2/lausitz-v2024.2-0.1pct.plans-initial.xml.gz",
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS")
			== 0 : "Must return non error code";

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());
	}

	@Test
	void runScenarioIncludingDrt() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		createDrtTestPopulation(inputPath);

		assert MATSimApplication.execute(LausitzDrtScenario.class, config,
			"--1pct",
			"--iterations", "0",
			"--config:plans.inputPlansFile", inputPath,
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS")
			== 0 : "Must return non error code";

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());
	}

	@Test
	void runScenarioIncludingAdditionalPtLine() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		createSinglePersonTestPopulation(config, TransportMode.pt);

		assert MATSimApplication.execute(LausitzPtScenario.class, config,
			"--1pct",
			"--iterations", "1",
			"--output", utils.getOutputDirectory(),
			"--config:plans.inputPlansFile", inputPath,
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS")
			== 0 : "Must return non error code";

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());

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

	@Test
	void runSpeedReductionScenario() {
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		assert MATSimApplication.execute(LausitzSpeedReductionScenario.class, config,
			"--1pct",
			"--iterations", "0",
			"--config:plans.inputPlansFile", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/input/v2024.2/lausitz-v2024.2-0.1pct.plans-initial.xml.gz",
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS")
			== 0 : "Must return non error code";

		Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
		Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());

		Network network = NetworkUtils.readNetwork(globFile(Path.of(utils.getOutputDirectory()), "*output_network.xml.gz").toString());

	//	motorway speed should not be altered
		Assertions.assertEquals(39.44, network.getLinks().get(Id.createLinkId("267472144")).getFreespeed());
		Assertions.assertEquals(27.78 * 0.6, network.getLinks().get(Id.createLinkId("-134417722")).getFreespeed());
		Assertions.assertEquals(10.4175 * 0.6, network.getLinks().get(Id.createLinkId("-67544888")).getFreespeed());
		Assertions.assertEquals(6.2475000000000005 * 0.6, network.getLinks().get(Id.createLinkId("-836484274")).getFreespeed());
	}

	@Test
	void runSingleModeScenario() {
		Set<String> modes = Set.of(TransportMode.car, TransportMode.bike, TransportMode.pt, TransportMode.walk);

		for (String mode : modes){
			Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
			ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

			createSinglePersonTestPopulation(config, mode);

			assert MATSimApplication.execute(LausitzSingleModeScenario.class, config,
				"--1pct",
				"--iterations", "1",
				"--config:plans.inputPlansFile", inputPath,
				"--output", utils.getOutputDirectory(),
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS",
				"--transport-mode", mode)
				== 0 : "Must return non error code";

			Assertions.assertTrue(new File(utils.getOutputDirectory()).isDirectory());
			Assertions.assertTrue(new File(utils.getOutputDirectory()).exists());

			Config outputConfig = ConfigUtils.loadConfig(globFile(Path.of(utils.getOutputDirectory()), "*output_config.xml").toString());

//		config should not have any smc modes + no smc strategy
			Assertions.assertEquals(1, outputConfig.subtourModeChoice().getModes().length);
			Assertions.assertEquals("", outputConfig.subtourModeChoice().getModes()[0]);
			Assertions.assertEquals(0, outputConfig.replanning().getStrategySettings()
				.stream()
				.filter(setting -> setting.getStrategyName().equals("SubtourModeChoice"))
				.toList()
				.size());

			Population population = PopulationUtils.readPopulation(globFile(Path.of(utils.getOutputDirectory()), "*output_plans.xml.gz").toString());

			TripStructureUtils.getTrips(population.getPersons().get(Id.createPersonId(personId)).getSelectedPlan())
				.forEach(t -> Assertions.assertEquals(mode, this.identifier.identifyMainMode(t.getTripElements())));
		}
	}

	private void createSinglePersonTestPopulation(Config config, String mode) {
		Population population = PopulationUtils.createPopulation(config);
		PopulationFactory fac = population.getFactory();
		Person person = fac.createPerson(personId);
		Plan plan = PopulationUtils.createPlan(person);

//		home in hoyerswerda
		Activity home = fac.createActivityFromCoord("home_2400", new Coord(863538.13,5711028.24));
		home.setEndTime(8 * 3600);
		Activity home2 = fac.createActivityFromCoord("home_2400", new Coord(863538.13,5711028.24));
		home2.setEndTime(19 * 3600);
//		work in cottbus
		Activity work = fac.createActivityFromCoord("work_2400", new Coord(867489.48,5746587.47));
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

		new PopulationWriter(population).write(this.inputPath);
	}

	private void createDrtTestPopulation(String inputPath) {
		Random random = new Random(1);
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Population population = scenario.getPopulation();
		PopulationFactory populationFactory = population.getFactory();

		for (int i = 0; i < 500; i++) {
			Person person = populationFactory.createPerson(Id.createPersonId("dummy_person_" + i));
			PersonUtils.setIncome(person, 1000.);
			Plan plan = populationFactory.createPlan();
			// a random location in the Hoyerswerda town center
			Activity fromAct = populationFactory.createActivityFromCoord("home_2400", new Coord(863949.91, 5711547.75));
			// a random time between 6:00-9:00
			fromAct.setEndTime(21600 + random.nextInt(10800));
			// set the link to PT, such that agent could find a potential intermodal trip
			Leg leg = populationFactory.createLeg(TransportMode.pt);
			// a location close to Cottbus Hbf
			Activity toAct = populationFactory.createActivityFromCoord("work_2400", new Coord(867341.75, 5746965.87));

			plan.addActivity(fromAct);
			plan.addLeg(leg);
			plan.addActivity(toAct);

			person.addPlan(plan);
			population.addPerson(person);
		}

		Person drtOnly = populationFactory.createPerson(Id.createPersonId("drtOnly"));
		PersonUtils.setIncome(drtOnly, 1000.);
		Plan plan = populationFactory.createPlan();
		// a random location in the Hoyerswerda town center
		Activity fromAct = populationFactory.createActivityFromCoord("home_2400", new Coord(863949.91, 5711547.75));
		// a random time between 6:00-9:00
		fromAct.setEndTime(21600 + random.nextInt(10800));
		Leg leg = populationFactory.createLeg(TransportMode.drt);
		// a location in Wittichenau
		Activity toAct = populationFactory.createActivityFromCoord("work_2400", new Coord(864808.3,5705774.7));

		plan.addActivity(fromAct);
		plan.addLeg(leg);
		plan.addActivity(toAct);

		drtOnly.addPlan(plan);
		population.addPerson(drtOnly);


		new PopulationWriter(population).write(inputPath);
	}

	private static final class PersonEntersPtVehicleEventHandler implements PersonEntersVehicleEventHandler {
		static List<PersonEntersVehicleEvent> enterEvents = new ArrayList<>();

		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			if (event.getPersonId().equals(personId) && event.getVehicleId().toString().contains("RE-VSP1")) {
				enterEvents.add(event);
			}
		}
	}
}
