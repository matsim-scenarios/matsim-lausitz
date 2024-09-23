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
import org.matsim.core.network.NetworkUtils;
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

		convertPtToDrtTrips(population, network, shp);

		PopulationUtils.writePopulation(population, output.toString());

		return 0;
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
