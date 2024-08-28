package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.matsim.run.LausitzScenario.*;

@CommandLine.Command(
	name = "adapt-freight-plans",
	description = "Adapt all freight plans (including small scall commercial traffic) to new standards."
)
public class AdaptFreightTrafficToDetailedModes implements MATSimAppCommand {

	Logger log = LogManager.getLogger(AdaptFreightTrafficToDetailedModes.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	public static void main(String[] args) {
		new AdaptFreightTrafficToDetailedModes().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());

		for (Person person : population.getPersons().values()) {
			if (PopulationUtils.getSubpopulation(person).equals("freight")) {
				adaptFreightPerson(person);
			} else if (PopulationUtils.getSubpopulation(person).equals(FREIGHT)) {
				for (Plan plan : person.getPlans()) {
					for (Leg leg : TripStructureUtils.getLegs(plan)) {
						if (!leg.getMode().equals(FREIGHT)) {
							leg.setMode(FREIGHT);
						}
					}
				}
			}

			if (PopulationUtils.getSubpopulation(person).contains("commercialPersonTraffic") ||
			PopulationUtils.getSubpopulation(person).contains("goodsTraffic")) {

				Map<String, Id<VehicleType>> types = VehicleUtils.getVehicleTypes(person);

				for (Plan plan : person.getPlans()) {
					for (Leg leg : TripStructureUtils.getLegs(plan)) {
						if (leg.getMode().equals(TransportMode.truck)) {
							String vehicleTypes = person.getAttributes().getAttribute("vehicleTypes").toString();
							if (vehicleTypes.contains("light8t")) {
								leg.setMode(LIGHT_MODE);
							} else if (vehicleTypes.contains("medium18t")) {
								leg.setMode(MEDIUM_MODE);
							} else if (vehicleTypes.contains("heavy40t")) {
								leg.setMode(HEAVY_MODE);
							} else {
								log.error("Unknown vehicle type in: {}", vehicleTypes);
								return 2;
							}
						} else if (leg.getMode().equals(TransportMode.car)) {
//							TODO
						}
					}
				}

				for (Map.Entry<String, Id<VehicleType>> entry : types.entrySet()) {
					if (Set.of(HEAVY_MODE, MEDIUM_MODE, LIGHT_MODE).contains(entry.getKey())) {
						types.put(entry.getKey(), Id.create(entry.getKey(), VehicleType.class));
					}
				}
			}
		}




		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	private static void adaptFreightPerson(Person person) {
		//			rename freight subpop to longDistanceFreight
		person.getAttributes().removeAttribute("subpopulation");
		person.getAttributes().putAttribute("subpopulation", FREIGHT);

//				rename each leg mode freight to longDistanceFreight
		for (Plan plan : person.getPlans()) {
			for (Leg leg : TripStructureUtils.getLegs(plan)) {
				if (leg.getMode().equals("freight")) {
					leg.setMode(FREIGHT);
				}
			}
		}
	}

	private static @NotNull Population removeSmallScaleCommercialTrafficFromPopulation(Population population) {
		Population newPop = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		for (Person person : population.getPersons().values()) {
			if (PopulationUtils.getSubpopulation(person).contains("commercialPersonTraffic")
			|| PopulationUtils.getSubpopulation(person).contains("goodsTraffic")) {
//				do not add commercial or goods traffic from RE
				continue;
			}
			newPop.addPerson(person);
		}
		return newPop;
	}
}
