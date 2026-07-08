package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

	public static void main(String[] args) {
		new SwitchBikeLegsToCar().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Population inputPop = PopulationUtils.readPopulation(input.toString());

		Population bikeUsers = PopulationUtils.createPopulation(ConfigUtils.createConfig());

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

		int legCount = 0;
		for (Person p : bikeUsers.getPersons().values()) {
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
		log.info("{} legs have been set from bike to car and their route was removed.", legCount);
		PopulationUtils.writePopulation(bikeUsers, output.toString());
		log.info("Population of bike users only has been written to {}", output);

		return 0;
	}
}
