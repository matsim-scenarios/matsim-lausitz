package org.matsim.drt;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEventHandler;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareCalculator;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareCalculator;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.HashMap;
import java.util.Map;

import static org.matsim.contrib.drt.fare.DrtFareHandler.PERSON_MONEY_EVENT_PURPOSE_DRT_FARE;

@Deprecated
/**
 *  The ChainedPtAndDrtFareHandler is a more elegant solution. Please use that instead!
 */
public class LausitzDrtFareHandler implements DrtRequestSubmittedEventHandler, PassengerDroppedOffEventHandler {
	@Inject
	private EventsManager events;
	private final FareZoneBasedPtFareCalculator zoneBasedPtFareCalculator;
	private final DistanceBasedPtFareCalculator distanceBasedPtFareCalculator;
	private final Map<Id<Request>, Double> openRequests = new HashMap<>();
	private final Network network;

	public LausitzDrtFareHandler(FareZoneBasedPtFareCalculator zoneBasedPtFareCalculator, DistanceBasedPtFareCalculator distanceBasedPtFareCalculator, Network network) {
		this.zoneBasedPtFareCalculator = zoneBasedPtFareCalculator;
		this.distanceBasedPtFareCalculator = distanceBasedPtFareCalculator;
		this.network = network;
	}

	/**
	 * For testing / stand-alone runs (i.e., without injection)
	 */
	LausitzDrtFareHandler(FareZoneBasedPtFareCalculator zoneBasedPtFareCalculator, DistanceBasedPtFareCalculator distanceBasedPtFareCalculator,
						  Network network, EventsManager events) {
		this.zoneBasedPtFareCalculator = zoneBasedPtFareCalculator;
		this.distanceBasedPtFareCalculator = distanceBasedPtFareCalculator;
		this.network = network;
		this.events = events;
	}

	@Override
	public void reset(int iteration) {
		DrtRequestSubmittedEventHandler.super.reset(iteration);
		openRequests.clear();
	}

	@Override
	public void handleEvent(DrtRequestSubmittedEvent event) {
		Coord fromCoord = network.getLinks().get(event.getFromLinkId()).getToNode().getCoord();
		Coord toCoord = network.getLinks().get(event.getToLinkId()).getToNode().getCoord();
		double estimatedFare;
		if (zoneBasedPtFareCalculator.calculateFare(fromCoord, toCoord).isPresent()) {
			estimatedFare = zoneBasedPtFareCalculator.calculateFare(fromCoord, toCoord).get().fare();
		} else {
			assert distanceBasedPtFareCalculator.calculateFare(fromCoord, toCoord).isPresent();
			estimatedFare = distanceBasedPtFareCalculator.calculateFare(fromCoord, toCoord).get().fare();
		}
		openRequests.put(event.getRequestId(), estimatedFare);
	}

	@Override
	public void handleEvent(PassengerDroppedOffEvent passengerDroppedOffEvent) {
		double time = passengerDroppedOffEvent.getTime();
		Id<Person> personId = passengerDroppedOffEvent.getPersonId();
		String mode = passengerDroppedOffEvent.getMode();
		Id<Request> requestId = passengerDroppedOffEvent.getRequestId();
		double estimatedFare = openRequests.get(requestId);

		events.processEvent(
			new PersonMoneyEvent(time, personId, -estimatedFare, PERSON_MONEY_EVENT_PURPOSE_DRT_FARE, mode, requestId + "")
		);
	}
}
