package org.matsim.run;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Run the Lausitz scenario including a new regional rail transit line between hoyerswerda and cottbus.
 * All necessary configs will be made in this class.
 */
public class LausitzPtScenario extends LausitzScenario {

	private final LausitzScenario baseScenario = new LausitzScenario(sample, emissions);

	public LausitzPtScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzPtScenario(@Nullable String args) {
		super(args);
	}

	public LausitzPtScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(LausitzPtScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		//		apply all config changes from base scenario class
		baseScenario.prepareConfig(config);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		//		apply all scenario changes from base scenario class
		baseScenario.prepareScenario(scenario);

//		pt stops are basically nodes, BUT are assigned to links
//		each pt stop is assigned a circle link to and from the same node.
//		the first "real" link has the to and from node from the circle link as fromNode

//		create pt network nodes
		Node hoyerswerda = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("pt_regio_159285.8"), new Coord(863538.8869276098, 5711014.921688189));
		Node hoyerswerdaNeustadt = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("pt_regio_135278.2"), new Coord(865993.0603089998, 5710758.211643816));
		Node spremberg = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("pt_regio_315415.7"), new Coord(873850.5174500551,5727598.197541567));
		Node bagenz = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("pt_short_2726.2"), new Coord(875299.1792959593,5736556.872374279));
		Node neuhausen = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("pt_short_2476.2"), new Coord(874002.1110680653, 5740162.957866353));
		Node cottbus = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("pt_short_5453.5"), new Coord(867315.8562388942, 5746748.406057102));

//		create links first direction
//		for speed and length deduction see internal documentation on drive
		Link startLink1 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_0"), hoyerswerda, hoyerswerda, scenario.getNetwork(), 50., 0.1, 100000.0, 1.0);
		Link link1 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_1"), hoyerswerda, hoyerswerdaNeustadt, scenario.getNetwork(), 2600., 2600./3*60, 100000.0, 1.0);
		Link link2 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_2"), hoyerswerdaNeustadt, spremberg, scenario.getNetwork(), 30000., 20., 100000.0, 1.0);
		Link link3 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_3"), spremberg, bagenz, scenario.getNetwork(), 10000., 10000./7*60, 100000.0, 1.0);
		Link link4 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_4"), bagenz, neuhausen, scenario.getNetwork(), 4000., 4000./4*60, 100000.0, 1.0);
		Link link5 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_5"), neuhausen, cottbus, scenario.getNetwork(), 10000., 10000./7*60, 100000.0, 1.0);

//		create links second direction
		Link startLink2 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_6"), cottbus, cottbus, scenario.getNetwork(), 50., 0.1, 100000.0, 1.0);
		Link link6 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_7"), cottbus, neuhausen, scenario.getNetwork(), 10000., 10000./7*60, 100000.0, 1.0);
		Link link7 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_8"), neuhausen, bagenz, scenario.getNetwork(), 4000., 4000./4*60, 100000.0, 1.0);
		Link link8 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_9"), bagenz, spremberg, scenario.getNetwork(), 10000., 10000./7*60, 100000.0, 1.0);
		Link link9 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_10"), spremberg, hoyerswerdaNeustadt, scenario.getNetwork(), 30000., 20., 100000.0, 1.0);
		Link link10 = NetworkUtils.createLink(Id.createLinkId("pt_vsp_11"), hoyerswerdaNeustadt, hoyerswerda, scenario.getNetwork(), 2600., 2600./3*60, 100000.0, 1.0);

		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory fac = schedule.getFactory();
		TransitLine line = fac.createTransitLine(Id.create("RE-VSP1", TransitLine.class));

		List<TransitInfo> transitInfoList = new ArrayList<>();

//		map with all new pt links and their respective travel time
		Map<Link, Double> travelTimes = new HashMap<>();
		travelTimes.put(startLink1, startLink1.getLength() / startLink1.getFreespeed());
		travelTimes.put(startLink2, startLink2.getLength() / startLink2.getFreespeed());
		travelTimes.put(link1, link1.getLength() / link1.getFreespeed());
		travelTimes.put(link2, link2.getLength() / link2.getFreespeed());
		travelTimes.put(link3, link3.getLength() / link3.getFreespeed());
		travelTimes.put(link4, link4.getLength() / link4.getFreespeed());
		travelTimes.put(link5, link5.getLength() / link5.getFreespeed());
		travelTimes.put(link6, link6.getLength() / link6.getFreespeed());
		travelTimes.put(link7, link7.getLength() / link7.getFreespeed());
		travelTimes.put(link8, link8.getLength() / link8.getFreespeed());
		travelTimes.put(link9, link9.getLength() / link9.getFreespeed());
		travelTimes.put(link10, link10.getLength() / link10.getFreespeed());

		int i = 0;
		for (List<Link> linkList : List.of(List.of(startLink1, link1, link2, link3, link4, link5), List.of(startLink2, link6, link7, link8, link9, link10))) {
			transitInfoList.add(configureTransitStops(linkList, scenario.getNetwork(), schedule, fac, i, travelTimes));
			i++;
		}

		int j = 0;
		for (TransitInfo info : transitInfoList) {
			TransitRoute route = fac.createTransitRoute(Id.create(line.getId() + "_" + j, TransitRoute.class), RouteUtils.createNetworkRoute(info.links), info.transitStops, "rail");

//			transit line should depart every hour to and from hoyerswerda
//			transit lines cottbus - spremberg / - ruhland operate 4/5am - 11pm -> pick similar frequency, start and end of operation interval

//			create departures for each route
			List<Departure> departures;
			if (j == 0) {
//				hoyerswerda -> cottbus possible connection to re10/43 frankfurt(oder) (every hour at :02), to re2 berlin (every hour at :04)
				departures = createDepartures(fac, info.travelTime, route, 2);
			} else {
//				cottbus -> hoyerswerda possible connection to re15 dresden (every hour at :33) and with 1 connection to Leipzig
				departures = createDepartures(fac, info.travelTime, route, 33);
			}
			//			create transit vehicle for each departure and add to transit vehicles
			departures.forEach(d -> {
				Vehicles vehicles = scenario.getTransitVehicles();
				VehicleType regio = vehicles.getVehicleTypes().get(Id.create("RE_RB_veh_type", VehicleType.class));
				Vehicle vehicle = vehicles.getFactory().createVehicle(Id.createVehicleId(d.getId().toString()), regio);
				vehicles.addVehicle(vehicle);
				d.setVehicleId(vehicle.getId());
				route.addDeparture(d);
			});
			line.addRoute(route);

			j++;
		}
		schedule.addTransitLine(line);
	}

	@Override
	protected void prepareControler(Controler controler) {
		//		apply all controller changes from base scenario class
		baseScenario.prepareControler(controler);
//		TODO: add potential new Listeners here
//		maybe a special controler for the new pt line would be handy?!
	}
	private TransitInfo configureTransitStops(List<Link> linkList, Network network, TransitSchedule schedule, TransitScheduleFactory fac, int count, Map<Link, Double> travelTimes) {
		List<Id<Link>> links = new ArrayList<>();
		List<TransitRouteStop> stops = new ArrayList<>();

		double travelTime = 0.;

		for (Link l : linkList) {
			l.setAllowedModes(Set.of(TransportMode.pt));
			network.addLink(l);

			Node toNode = l.getToNode();

//				create stop with to node id minus prefix pt_
			TransitStopFacility stopFac = fac.createTransitStopFacility(Id.create(toNode.getId().toString().substring(toNode.getId().toString().indexOf("pt_")
				+ "pt_".length()) + "_" + count, TransitStopFacility.class), toNode.getCoord(), false);
			stopFac.setLinkId(l.getId());
			schedule.addStopFacility(stopFac);

			double arrivalDelay = 0.;
			double departureDelay = 0.;

			if (!l.getId().toString().equals("pt_vsp_0") && !l.getId().toString().equals("pt_vsp_6")) {
//					delay for fictive start links = 0, else see below
				travelTime += travelTimes.get(l);
				arrivalDelay = travelTime;
				departureDelay = arrivalDelay + 60;
			}

			TransitRouteStop stop = fac.createTransitRouteStop(stopFac, arrivalDelay, departureDelay);
			stop.setAwaitDepartureTime(true);
			stops.add(stop);
			links.add(l.getId());
		}
		return new TransitInfo(links, stops, travelTime);
	}

	private List<Departure> createDepartures(TransitScheduleFactory fac, double travelTime, TransitRoute route, int connectionTrainDeparture) {
		List<Departure> departures = new ArrayList<>();

		for (int k = 0; k <= 24; k++) {
			int arrival = (k * 60 + connectionTrainDeparture - 10) * 60;

//					no connection before 4am
			if (arrival < 4 * 3600 || arrival > 24 * 3600) {
				continue;
			}

//					departure time = arrival - (travelTime - 1min per intermediate stop for entering / leaving passengers)
//					departure id = route id + prefix pt_ and suffix consecutive number
			double departureTime = arrival - (travelTime + (route.getStops().size() - 2) * 60.);
			Departure departure = fac.createDeparture(Id.create("pt_" + route.getId() + "_" + k, Departure.class), departureTime);
			departures.add(departure);
		}

		return departures;
	}

	private record TransitInfo(List<Id<Link>> links, List<TransitRouteStop> transitStops, Double travelTime) {

	}
}
