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
	private static final String URL = String.format("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/lausitz/input/v%s/",
		LausitzScenario.VERSION);
	private static final Id<Person> PERSON_ID = Id.createPersonId("642052");

	@Disabled("Test is used to secure functionality of PrepareDrtScenarioAgents. Therefore, " +
		"it does not have to run after every commit and it is disabled and only run manually. -sme1024")
	@Test
	void testPrepareDrtScenarioAgents() {
		String inputPopulationPath = String.format("./input/v%s/lausitz-pt-case-test_plans_1person.xml.gz", LausitzScenario.VERSION);
		Population in = PopulationUtils.readPopulation(inputPopulationPath);
//		String networkPath = URL + String.format("lausitz-v%s-network-with-pt.xml.gz", LausitzScenario.VERSION);
		String outPath = utils.getOutputDirectory() + "/drt-test-population.xml.gz";

		assert MATSimApplication.execute(LausitzScenario.class, "prepare", "prepare-drt-agents",
			inputPopulationPath,
			"--output", outPath)
			== 0 : "Must return non error code";

		Population out = PopulationUtils.readPopulation(outPath);
//		List<PlanElement> outSelectedPlanElements = out.getPersons().get(PERSON_ID).getSelectedPlan().getPlanElements();
		List<TripStructureUtils.Trip> outTrips = TripStructureUtils.getTrips(out.getPersons().get(PERSON_ID).getSelectedPlan());
		List<TripStructureUtils.Trip> inTrips = TripStructureUtils.getTrips(in.getPersons().get(PERSON_ID).getSelectedPlan());

//		there is only 1 person in the population
		for (int index : PrepareDrtScenarioAgents.getNewPtLineTripIndexes(in.getPersons().get(PERSON_ID).getSelectedPlan())) {
//			TODO: run test and check if overwriting of selected plan works. ALso test if getPlanElements is immutable or not.
			TripStructureUtils.Trip outTrip = outTrips.get(index);

			Assertions.assertEquals(inTrips.get(index).getOriginActivity(), outTrip.getOriginActivity());
			Assertions.assertEquals(inTrips.get(index).getDestinationActivity(), outTrip.getDestinationActivity());
//			trip should only consist of start and end act + new drt leg in between
			Assertions.assertEquals(3, outTrip.getTripElements().size());
			Assertions.assertEquals(1, outTrip.getLegsOnly().size());
			Assertions.assertInstanceOf(Leg.class, outTrip.getTripElements().get(1));
			Assertions.assertEquals(TransportMode.drt, ((Leg) outTrip.getTripElements().get(1)).getMode());

////			access leg
//			Assertions.assertInstanceOf(Leg.class, outSelectedPlanElements.get(index - 2));
//			Leg access = (Leg) outSelectedPlanElements.get(index - 2);
//			Assertions.assertNull(access.getRoute());
//			Assertions.assertEquals(TransportMode.drt, access.getRoutingMode());
//
////			interaction act before leg
//			Assertions.assertInstanceOf(Activity.class, outSelectedPlanElements.get(index - 1));
//			Activity before = (Activity) outSelectedPlanElements.get(index - 1);
//			Assertions.assertNull(before.getFacilityId());
//			Assertions.assertEquals("drt interaction", before.getType());
//
////			pt leg which was converted to drt leg
//			Assertions.assertInstanceOf(Leg.class, outSelectedPlanElements.get(index));
//			Leg leg = (Leg) outSelectedPlanElements.get(index);
//			Assertions.assertNull(leg.getRoute());
//			Assertions.assertEquals(TransportMode.drt, leg.getMode());
//
//			//			interaction act after leg
//			Assertions.assertInstanceOf(Activity.class, outSelectedPlanElements.get(index + 1));
//			Activity after = (Activity) outSelectedPlanElements.get(index + 1);
//			Assertions.assertNull(after.getFacilityId());
//			Assertions.assertEquals("drt interaction", after.getType());
//
//			//			egress leg
//			Assertions.assertInstanceOf(Leg.class, outSelectedPlanElements.get(index + 2));
//			Leg egress = (Leg) outSelectedPlanElements.get(index + 2);
//			Assertions.assertNull(egress.getRoute());
//			Assertions.assertEquals(TransportMode.drt, egress.getRoutingMode());
		}
	}
}
