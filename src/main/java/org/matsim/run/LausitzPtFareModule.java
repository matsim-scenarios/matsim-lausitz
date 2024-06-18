package org.matsim.run;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import playground.vsp.pt.fare.DistanceBasedPtFareHandler;
import playground.vsp.pt.fare.DistanceBasedPtFareParams;
import playground.vsp.pt.fare.PtFareConfigGroup;
import playground.vsp.pt.fare.PtFareUpperBoundHandler;

/**
 * Provides pt fare calculation for the lausitz model.
 */
public class LausitzPtFareModule extends AbstractModule {

//	sources for price values:
//	https://www.vbb.de/tickets/monatskarten/vbb-umweltkarte/
//	for some example trips, which were used to calc (bahn.de) the below values see internal documentation

//	single trip fare (<30km): mean(5.40,2.85)
	static final double NORMAL_BASE_FARE = 4.125;
//	shorter trips do not have a slope in this scenario
	static final double NORMAL_TRIP_SLOPE = 0.000;
// agents always pay normalBaseFare minimum as there is no distance based cost for trips <30km
	static final double MIN_FARE = NORMAL_BASE_FARE;
//	for trips of +-30km there seems to be a somewhat higher pt price.
//	the threshold is also chosen because Ruhland - Hoyerswerda falls just under the 30km mark.
//	if the above connection were a longer = more expensive trip, it would increase the attractivity of the DRT service (policy case) immensly
	static final int LONG_DISTANCE_THRESHOLD = 30000;
//	single trip fare (>=30km): mean(20.30,30)
	static final double LONG_BASE_FARE = 25.15;
//	max. price: 46€ for 108km (longest rail distance in study area Eisenhüttenstadt <-> Bautzen) compared to mean price 25.15€ for 30km
//	=> slope for distance based cost: 0,2673076923€
	static final double LONG_TRIP_SLOPE = 0.2673076923;
//		upper bound factor: relation of single trip compared to daily cost of monthly VBB ticket
//		5.12 / 4.125 = 1.24
	static final double UPPER_BOUND_FACTOR = 1.24;

	@Override
	public void install() {
		// Set the money related thing in the config (planCalcScore/scoring) file to 0.
		getConfig().scoring().getModes().get(TransportMode.pt).setDailyMonetaryConstant(0);

		// Initialize config group (and also write in the output config)
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(this.getConfig(), PtFareConfigGroup.class);
		DistanceBasedPtFareParams distanceBasedPtFareParams = ConfigUtils.addOrGetModule(this.getConfig(), DistanceBasedPtFareParams.class);

		// Set parameters
		ptFareConfigGroup.setApplyUpperBound(true);
		ptFareConfigGroup.setUpperBoundFactor(UPPER_BOUND_FACTOR);

		// Minimum fare (e.g. short trip or 1 zone ticket)
		distanceBasedPtFareParams.setMinFare(MIN_FARE);
		// Division between long trip and short trip (unit: m)
		distanceBasedPtFareParams.setLongDistanceTripThreshold(LONG_DISTANCE_THRESHOLD);

		// y = ax + b --> a value, for short trips
		distanceBasedPtFareParams.setNormalTripSlope(NORMAL_TRIP_SLOPE);
		// y = ax + b --> b value, for short trips
		distanceBasedPtFareParams.setNormalTripIntercept(NORMAL_BASE_FARE);

		// Base price is the daily ticket for long trips
		// y = ax + b --> a value, for long trips
		distanceBasedPtFareParams.setLongDistanceTripSlope(LONG_TRIP_SLOPE);
		// y = ax + b --> b value, for long trips
		distanceBasedPtFareParams.setLongDistanceTripIntercept(LONG_BASE_FARE);


		// Add bindings
		addEventHandlerBinding().toInstance(new DistanceBasedPtFareHandler(distanceBasedPtFareParams));
		if (ptFareConfigGroup.getApplyUpperBound()) {
			PtFareUpperBoundHandler ptFareUpperBoundHandler = new PtFareUpperBoundHandler(ptFareConfigGroup.getUpperBoundFactor());
			addEventHandlerBinding().toInstance(ptFareUpperBoundHandler);
			addControlerListenerBinding().toInstance(ptFareUpperBoundHandler);
		}
	}
}
