package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.population.CleanPopulation;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@CommandLine.Command(
	name = "switch-bike-legs-to-car",
	description = "Cut out bike users and switch all bike legs to car.."
)
public class SwitchBikeLegsToCar implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(SwitchBikeLegsToCar.class);
	private final TripsToLegsAlgorithm trips2Legs = new TripsToLegsAlgorithm(new RoutingModeMainModeIdentifier());

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;
	@CommandLine.Option(names = "--cut-out", description = "cut out or not bike users from population")
	private CutOutHandling cutOut = CutOutHandling.CUT_OUT_BIKE_USERS;
	@CommandLine.Option(names = "--switch-bike-to-car", description = "switch bike trips to car or not")
	private SwitchBikeToCarHandling switchBikeToCarHandling = SwitchBikeToCarHandling.SWITCH_BIKE_TO_CAR;
	@CommandLine.Option(names = "--agent-ids", split = ",", description = "Ids of agents of interest. when used only these agents are changed.")
	Set<String> agentsOfInterest = null;

	public static void main(String[] args) {
		new SwitchBikeLegsToCar().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Population inputPop = PopulationUtils.readPopulation(input.toString());

		Population bikeUsers = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		if (cutOut == CutOutHandling.CUT_OUT_BIKE_USERS) {
			cutOutBikeUsersFromPopulation(inputPop, bikeUsers);
		} else if (cutOut == CutOutHandling.DONT_CUT_OUT_BIKE_USERS) {
			bikeUsers = inputPop;
		}

		if (switchBikeToCarHandling == SwitchBikeToCarHandling.SWITCH_BIKE_TO_CAR) {
			switchBikeTripsToCarTrips(bikeUsers, agentsOfInterest);
		}

		PopulationUtils.writePopulation(bikeUsers, output.toString());
		log.info("Population of bike users only has been written to {}", output);

		return 0;
	}

	/**
	 * switch bike trips to car.
	 */
	private void switchBikeTripsToCarTrips(Population bikeUsers, Set<String> agentsOfInterest) {
		Set<Id<Person>> agents = new HashSet<>();

		if (agentsOfInterest == null) {
			agents.addAll(bikeUsers.getPersons().keySet());
		} else {
			agentsOfInterest.forEach(p -> agents.add(Id.createPersonId(p)));
		}

		int legCount = 0;
		for (Person p : bikeUsers.getPersons().values()) {
			if (agents.contains(p.getId())) {
				for (Plan pl : p.getPlans()) {
	//				we need to get rid of access/egress legs and interaction acts
					trips2Legs.run(pl);

					for (Leg l : TripStructureUtils.getLegs(pl)) {
						if (l.getMode().equals(TransportMode.bike)) {
							CleanPopulation.removeRouteFromLeg(l);
							l.setMode(TransportMode.car);
							l.setRoutingMode(TransportMode.car);
							legCount++;
						}
					}
				}
			}
		}
		log.info("{} legs have been set from bike to car and their route was removed.", legCount);
	}

	/**
	 * cut out bike users from population.
	 */
	private void cutOutBikeUsersFromPopulation(Population inputPop, Population bikeUsers) {
		for (Person p : inputPop.getPersons().values()) {
			Plan selected = p.getSelectedPlan();

			for (Leg l : TripStructureUtils.getLegs(selected)) {
				if (l.getMode().equals(TransportMode.bike)) {
					bikeUsers.addPerson(p);
					break;
				}
			}
		}
		log.info("Population of bike users only has {} persons.", bikeUsers.getPersons().size());
	}

	/**
	 * Helper enum to enable/disable cutout bike users.
	 */
	public enum CutOutHandling {CUT_OUT_BIKE_USERS, DONT_CUT_OUT_BIKE_USERS}

	/**
	 * Helper enum to enable/disable switching of bike trips to car.
	 */
	public enum SwitchBikeToCarHandling {SWITCH_BIKE_TO_CAR, DONT_SWITCH_BIKE_TO_CAR}
}
