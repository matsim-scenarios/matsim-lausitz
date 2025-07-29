package org.matsim.run.prepare;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimApplication;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.run.scenarios.LausitzScenario;
import org.matsim.testcases.MatsimTestUtils;

import java.util.List;

class PrepareDrtScenarioAgentsTest {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();
	private static final Id<Person> PERSON_ID = Id.createPersonId("642052");

	@Disabled("Test is used to secure functionality of PrepareDrtScenarioAgents. Therefore, " +
		"it does not have to run after every commit and it is disabled and only run manually. -sme1024")
	@Test
	void testPrepareDrtScenarioAgents() {
		String inputPopulationPath = String.format("./input/v%s/lausitz-pt-case-test_plans_1person.xml.gz", LausitzScenario.VERSION);
		Population in = PopulationUtils.readPopulation(inputPopulationPath);
		String outPath = utils.getOutputDirectory() + "/drt-test-population.xml.gz";

		assert MATSimApplication.execute(LausitzScenario.class, "prepare", "prepare-drt-agents",
			inputPopulationPath,
			"--output", outPath)
			== 0 : "Must return non error code";

		Population out = PopulationUtils.readPopulation(outPath);
		List<TripStructureUtils.Trip> outTrips = TripStructureUtils.getTrips(out.getPersons().get(PERSON_ID).getSelectedPlan());
		List<TripStructureUtils.Trip> inTrips = TripStructureUtils.getTrips(in.getPersons().get(PERSON_ID).getSelectedPlan());

//		there is only 1 person in the population
		for (int index : PrepareDrtScenarioAgents.getNewPtLineTripIndexes(in.getPersons().get(PERSON_ID).getSelectedPlan())) {
			TripStructureUtils.Trip outTrip = outTrips.get(index);

			Assertions.assertEquals(inTrips.get(index).getOriginActivity().toString(), outTrip.getOriginActivity().toString());
			Assertions.assertEquals(inTrips.get(index).getDestinationActivity().toString(), outTrip.getDestinationActivity().toString());
//			trip should only consist of start and end act + new drt leg in between. Apparently start and end act do not count. So expected size = 1
			Assertions.assertEquals(1, outTrip.getTripElements().size());
			Assertions.assertEquals(1, outTrip.getLegsOnly().size());
			Assertions.assertInstanceOf(Leg.class, outTrip.getTripElements().getFirst());
			Assertions.assertEquals(TransportMode.drt, ((Leg) outTrip.getTripElements().getFirst()).getMode());
		}
	}
}
