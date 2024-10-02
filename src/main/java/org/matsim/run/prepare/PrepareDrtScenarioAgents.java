package org.matsim.run.prepare;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
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
	private Path networkPath;
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;
	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	private static final String PLAN_TYPE = "drtPlan";
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
		Network network = NetworkUtils.readNetwork(networkPath.toString());

//		convertPtToDrtTrips(population, network, shp);

//		TODO: try if for 3 and 5 it is enough to delete act locations instead of searching for nearest drt link
		convertVspRegionalTrainLegsToDrt(population, network);

		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	private void convertVspRegionalTrainLegsToDrt(Population population, Network network) {
//		shp needs to include all locations, where the new pt line (from pt policy case) has a station
//		thus, lausitz.shp should be chosen as an input
		PrepareNetwork.prepareDrtNetwork(network, shp.getShapeFile());

		NetworkFilterManager manager = new NetworkFilterManager(network, new NetworkConfigGroup());
		manager.addLinkFilter(l -> l.getAllowedModes().contains(TransportMode.drt));
		Network filtered = manager.applyFilters();

		for (Person person : population.getPersons().values()) {
//			TODO: replace with static call of CleanPopulation method
			Plan selected = person.getSelectedPlan();
			for (Plan plan : Lists.newArrayList(person.getPlans())) {
				if (plan != selected)
					person.removePlan(plan);
			}

//			get indexes of pt legs with new pt line
			List<Integer> indexes = TripStructureUtils.getLegs(selected).stream()
				.filter(l -> l.getRoute().getStartLinkId().toString().contains("pt_vsp_")
					&& l.getRoute().getEndLinkId().toString().contains("pt_vsp_"))
				.map(l -> selected.getPlanElements().indexOf(l)).toList();

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
						leg.setRoute(null);
						leg.setRoutingMode(TransportMode.drt);
						continue;
					}

					if (i == index - 1 && selected.getPlanElements().get(i) instanceof Activity act) {
//						interaction act before leg
						if (!act.getType().equals(PT_INTERACTION)) {
							logNotPtInteractionAct(person, act, i);
							throw new IllegalStateException();
						}

						if (selected.getPlanElements().get(i - 2) instanceof Activity prev) {
							convertToDrtInteraction(act, prev, network, filtered);
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
						continue;
					}

					if (i == index + 1 && selected.getPlanElements().get(i) instanceof Activity act) {
//						interaction act before leg
						if (!act.getType().equals(PT_INTERACTION)) {
							logNotPtInteractionAct(person, act, i);
							throw new IllegalStateException();
						}
						if (selected.getPlanElements().get(i + 2) instanceof Activity prev) {
							convertToDrtInteraction(act, prev, network, filtered);
						} else {
							logWrongPlanElementType(person, i);
							throw new IllegalStateException();
						}
					}
				}
			}
		}
	}

	private static void logNotPtInteractionAct(Person person, Activity act, int i) {
		log.fatal("For selected plan of person {} type {} expected for activity at index {}. Activity has type {} instead. Abort.",
			person.getId(), PT_INTERACTION, i, act.getType());
	}

	private static void logWrongPlanElementType(Person person, int i) {
		log.fatal("For the selected plan of person {} the plan element with index {} was expected to be an activity." +
			"It seems to be a leg. Abort.", person.getId(), i);
	}

	private static void convertToDrtInteraction(Activity act, Activity previous, Network fullNetwork, Network filtered) {
//							TODO: test if it is enough to delete link and facility, but keep coord. Correct link shoulb be found automatically then

		if (filtered.getLinks().containsKey(previous.getLinkId())) {
			act.setLinkId(previous.getLinkId());
		} else {
			act.setLinkId(NetworkUtils.getNearestLink(filtered, fullNetwork.getLinks().get(previous.getLinkId()).getToNode().getCoord()).getId());
		}
		act.setFacilityId(null);
		act.setCoord(null);
		act.setType("drt interaction");
	}

	/**
	 * This is implemented as a separate method to be able to use it in a scenario run class.
	 * Additionally, it can be used to write a new output population by calling this class.
	 */
	public static void convertPtToDrtTrips(Population population, Network network, ShpOptions shp) {
		Geometry serviceArea = shp.getGeometry();

		AnalysisMainModeIdentifier identifier = new DefaultAnalysisMainModeIdentifier();

		log.info("Starting to iterate through population.");

		int count = 0;
		for (Person person : population.getPersons().values()) {
			Plan selected = person.getSelectedPlan();
//			remove all unselected plans
			for (Plan plan : Lists.newArrayList(person.getPlans())) {
				if (plan != selected)
					person.removePlan(plan);
			}

			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(selected)) {

				String tripMode = identifier.identifyMainMode(trip.getTripElements());

				if (!tripMode.equals(TransportMode.pt)) {
					continue;
				}

				boolean startInside = isInside(network.getLinks().get(trip.getLegsOnly().getFirst().getRoute().getStartLinkId()), serviceArea);
				boolean endInside = isInside(network.getLinks().get(trip.getLegsOnly().getLast().getRoute().getEndLinkId()), serviceArea);

//				we only need to change the mode for trips within the drt service area.
//				All others will be handled by intermodal trips between drt and pt.
//				"other" would be ending in service area but not starting and vice versa
				if (startInside && endInside) {
					int oldIndex = selected.getPlanElements().indexOf(trip.getLegsOnly().stream().filter(l -> l.getMode().equals(TransportMode.pt)).toList().getFirst());

//					TODO: erst plan kopieren dann converten
					int index = convertPtTripToLeg(trip, selected, identifier);

//					copy pt plan and create drt plan. Tag it as drtPlan
					Plan drtCopy = person.createCopyOfSelectedPlanAndMakeSelected();
					((Leg) drtCopy.getPlanElements().get(index)).setMode(TransportMode.drt);
					drtCopy.setType(PLAN_TYPE);
					count++;
				}
			}
		}
		log.info("For {} trips, a copy of the selected plan with a drt trip has been created.", count);
	}

	private static int convertPtTripToLeg(TripStructureUtils.Trip trip, Plan selected, AnalysisMainModeIdentifier identifier) {
		final List<PlanElement> planElements = selected.getPlanElements();

//		TODO: test if new leg is pasted at correct index.
//		TODO: index in this method is always -1. fix this

//		TODO: rather use trips2LegsALgo instead of copy paste
		final List<PlanElement> fullTrip =
			planElements.subList(
				planElements.indexOf(trip.getOriginActivity()) + 1,
				planElements.indexOf(trip.getDestinationActivity()));
		final String mode = identifier.identifyMainMode(fullTrip);
		fullTrip.clear();
		Leg leg = PopulationUtils.createLeg(mode);
		TripStructureUtils.setRoutingMode(leg, mode);
		int index = planElements.indexOf(leg);
		fullTrip.add(leg);
		if ( fullTrip.size() != 1 ) throw new IllegalArgumentException(fullTrip.toString());
		return index;
	}

	private static boolean isInside(Link link, Geometry geometry) {
		return MGC.coord2Point(link.getFromNode().getCoord()).within(geometry) ||
			MGC.coord2Point(link.getToNode().getCoord()).within(geometry);
	}
}
