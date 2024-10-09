package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.population.CleanPopulation;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.run.DrtOptions;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "prepare-drt-agents",
	description = "Manually create drt plans for agents with pt trips within the drt service area."
)
public class PrepareDrtScenarioAgents implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(PrepareDrtScenarioAgents.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;
	@CommandLine.Option(names = "--network", description = "Path to network", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;
	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	private static final String PT_INTERACTION = "pt interaction";

	public static void main(String[] args) {
		new PrepareDrtScenarioAgents().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		if (!Files.exists(input)) {
			log.error("Input population does not exist: {}", input);
			return 2;
		}

		if (!shp.isDefined()) {
			log.error("service area shape file is not defined: {}", shp);
			return 2;
		}

		Population population = PopulationUtils.readPopulation(input.toString());
		Network network = NetworkUtils.readNetwork(networkPath);

		//		shp needs to include all locations, where the new pt line (from pt policy case) has a station
//		thus, lausitz.shp should be chosen as an input
		PrepareNetwork.prepareDrtNetwork(network, shp.getShapeFile());

//		TODO: try if for 3 and 5 it is enough to delete act locations instead of searching for nearest drt link
		convertVspRegionalTrainLegsToDrt(population, network);

		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	/**
	 * Method to convert agents, which are using the new vsp pt line (see RunLausitzPtScenario) manually to mode DRT.
	 * The network needs to be including DRT as an allowed mode.
	 */
	public static void convertVspRegionalTrainLegsToDrt(Population population, Network networkInclDrt) {
		NetworkFilterManager manager = new NetworkFilterManager(networkInclDrt, new NetworkConfigGroup());
		manager.addLinkFilter(l -> l.getAllowedModes().contains(TransportMode.drt));
		Network filtered = manager.applyFilters();

		for (Person person : population.getPersons().values()) {
			CleanPopulation.removeUnselectedPlans(person);
			Plan selected = person.getSelectedPlan();

//			get indexes of pt legs with new pt line
			List<Integer> indexes = getNewPtLineIndexes(selected);

//			only remove routes from legs if no legs with new vsp pt line
			if (indexes.isEmpty()) {
				TripStructureUtils.getLegs(selected).forEach(CleanPopulation::removeRouteFromLeg);
				continue;
			}

			for (Integer index : indexes) {
				for (int i = 0; i < selected.getPlanElements().size(); i++) {
					if ((i == index - 2 || i == index + 2) && selected.getPlanElements().get(i) instanceof Leg leg) {
//						access / egress walk leg
						if (!(leg.getMode().equals(TransportMode.walk) && leg.getRoutingMode().equals(TransportMode.pt))) {
							log.fatal("For selected plan of person {} mode {} and routing mode {} expected for leg at index {}. " +
									"Leg has mode {} and routing mode {} instead. Abort.",
								person.getId(), TransportMode.walk, TransportMode.pt, i, leg.getMode(), leg.getRoutingMode());
							throw new IllegalStateException();
						}
						CleanPopulation.removeRouteFromLeg(leg);
						leg.setRoutingMode(TransportMode.drt);
						leg.setTravelTimeUndefined();
						leg.setDepartureTimeUndefined();
						continue;
					}

					if (i == index - 1 && selected.getPlanElements().get(i) instanceof Activity act) {
//						interaction act before leg
						if (!act.getType().equals(PT_INTERACTION)) {
							logNotPtInteractionAct(person, act, i);
							throw new IllegalStateException();
						}

						if (selected.getPlanElements().get(i - 2) instanceof Activity prev) {
							convertToDrtInteractionAndSplitTrip(act, prev, filtered);
						} else {
							logWrongPlanElementType(person, i);
							throw new IllegalStateException();
						}
						continue;
					}

					if (i == index && selected.getPlanElements().get(i) instanceof Leg leg) {
//						pt leg with new pt line
						leg.setRoute(null);
						leg.setMode(TransportMode.drt);
						leg.setRoutingMode(TransportMode.drt);
						leg.setTravelTimeUndefined();
						leg.setDepartureTimeUndefined();
						leg.getAttributes().removeAttribute("enterVehicleTime");
						continue;
					}

					if (i == index + 1 && selected.getPlanElements().get(i) instanceof Activity act) {
//						interaction act after leg
						if (!act.getType().equals(PT_INTERACTION)) {
							logNotPtInteractionAct(person, act, i);
							throw new IllegalStateException();
						}
						if (selected.getPlanElements().get(i + 2) instanceof Activity next) {
							convertToDrtInteractionAndSplitTrip(act, next, filtered);
						} else {
							logWrongPlanElementType(person, i);
							throw new IllegalStateException();
						}
					}
				}
			}
		}
	}

	public static @NotNull List<Integer> getNewPtLineIndexes(Plan selected) {
		return TripStructureUtils.getLegs(selected).stream()
			.filter(l -> l.getRoute().getStartLinkId().toString().contains("pt_vsp_")
				&& l.getRoute().getEndLinkId().toString().contains("pt_vsp_"))
			.map(l -> selected.getPlanElements().indexOf(l)).toList();
	}

	private static void logNotPtInteractionAct(Person person, Activity act, int i) {
		log.fatal("For selected plan of person {} type {} expected for activity at index {}. Activity has type {} instead. Abort.",
			person.getId(), PT_INTERACTION, i, act.getType());
	}

	private static void logWrongPlanElementType(Person person, int i) {
		log.fatal("For the selected plan of person {} the plan element with index {} was expected to be an activity." +
			"It seems to be a leg. Abort.", person.getId(), i);
	}

	private static void convertToDrtInteractionAndSplitTrip(Activity act, Activity previous, Network filtered) {
//							TODO: test if it is enough to delete link and facility, but keep coord. Correct link shoulb be found automatically then

//		The original trip has to be split up because MATSim does not allow trips with 2 different routing modes.
//		for the drt subtrip, a dummy act, which is not scored, is created.
		if (TripStructureUtils.isStageActivityType(previous.getType())) {
			previous.setType(DrtOptions.DRT_DUMMY_ACT_TYPE);
			previous.setFacilityId(null);
			previous.setLinkId(null);
		}
		act.setLinkId(NetworkUtils.getNearestLink(filtered, previous.getCoord()).getId());
		act.setFacilityId(null);
		act.setCoord(null);
		act.setType("drt interaction");
	}
}
