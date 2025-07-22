package org.matsim.drt;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.vsp.pt.fare.ChainedPtFareCalculator;
import org.matsim.contrib.vsp.pt.fare.PtFareCalculator;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareHandler;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.pt.PtConstants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Modified based on the ChainedPtFareHandler from matsim-lib.
 */
public class ChainedPtAndDrtFareHandler implements PtFareHandler {
	@Inject
	private EventsManager events;
	public static final String DRT_INTERACTION = ScoringConfigGroup.createStageActivityType(TransportMode.drt);
	public static final String DRT_OR_PT_FARE = "drt or drt-pt intermodal fare";

	private final Map<Id<Person>, Coord> personDepartureCoordMap = new HashMap<>();
	private final Map<Id<Person>, Coord> personArrivalCoordMap = new HashMap<>();
	private final Set<Id<Person>> personInvolvedInDrtTrip = new HashSet<>();

	@Inject
	private ChainedPtFareCalculator fareCalculator;

	@Override
	public void handleEvent(ActivityStartEvent event) {
		String eventType = event.getActType();

		if (eventType.equals(PtConstants.TRANSIT_ACTIVITY_TYPE) || eventType.equals(DRT_INTERACTION)) {
			personDepartureCoordMap.computeIfAbsent(event.getPersonId(), c -> event.getCoord());
			// The departure place is fixed to the place of
			// first pt interaction an agent has in the whole leg
			personArrivalCoordMap.put(event.getPersonId(), event.getCoord());
			// The arrival stop will keep updating until the agent start a real
			// activity (i.e. finish the leg)
			if (eventType.equals(DRT_INTERACTION)) {
				personInvolvedInDrtTrip.add(event.getPersonId());
			}
		}

		if (StageActivityTypeIdentifier.isStageActivity(event.getActType())) {
			return;
		}

		Id<Person> personId = event.getPersonId();
		if (!personDepartureCoordMap.containsKey(personId)) {
			return;
		}

		Coord from = personDepartureCoordMap.get(personId);
		Coord to = personArrivalCoordMap.get(personId);

		PtFareCalculator.FareResult fare = fareCalculator.calculateFare(from, to).orElseThrow();

		// charge fare to the person
		events.processEvent(new PersonMoneyEvent(event.getTime(), event.getPersonId(), -fare.fare(),
			personInvolvedInDrtTrip.contains(personId)? DRT_OR_PT_FARE : PtFareConfigGroup.PT_FARE,
			fare.transactionPartner(), event.getPersonId().toString()));

		personDepartureCoordMap.remove(personId);
		personArrivalCoordMap.remove(personId);
		personInvolvedInDrtTrip.remove(personId);
	}

	@Override
	public void handleEvent(AgentWaitingForPtEvent event) {
		//TODO
	}

	@Override
	public void reset(int iteration) {
		personArrivalCoordMap.clear();
		personDepartureCoordMap.clear();
		personInvolvedInDrtTrip.clear();
	}
}
