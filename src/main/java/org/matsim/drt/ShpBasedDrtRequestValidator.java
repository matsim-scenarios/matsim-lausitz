package org.matsim.drt;

import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.dvrp.passenger.PassengerRequest;
import org.matsim.contrib.dvrp.passenger.PassengerRequestValidator;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.matsim.contrib.dvrp.passenger.DefaultPassengerRequestValidator.EQUAL_FROM_LINK_AND_TO_LINK_CAUSE;

/**
 * In addition to the DefaultPassengerRequestValidator, we also check if the trip is allowed.
 * A trip is allowed if at least one end of the trip is within the main service area:
 * Attribute name: area_type, Attribute value: main.
 */
public class ShpBasedDrtRequestValidator implements PassengerRequestValidator {
	private static final String TRIP_NOT_ALLOWED = "trip_not_allowed";
	private final ShpOptions shp;
	private final List<Geometry> mainServiceAreas;

	public ShpBasedDrtRequestValidator(ShpOptions shp) {
		this.shp = shp;
		this.mainServiceAreas = getMainServiceAreas();
	}

	private List<Geometry> getMainServiceAreas() {
		List<Geometry> mainServiceAreas = new ArrayList<>();
		for (SimpleFeature feature : shp.readFeatures()) {
			if (feature.getDefaultGeometry() instanceof Geometry geometry) {
				if (feature.getAttribute("area_type").toString().equals("main")) {
					mainServiceAreas.add(geometry);
				}
			}
		}
		return mainServiceAreas;
	}

	@Override
	public Set<String> validateRequest(PassengerRequest passengerRequest) {
		// same as in DefaultPassengerRequestValidator, the request is invalid if fromLink == toLink
		if (passengerRequest.getFromLink() == passengerRequest.getToLink()) {
			return Collections.singleton(EQUAL_FROM_LINK_AND_TO_LINK_CAUSE);
		}

		// check if at least one end of the trip is within any of the main service areas (i.e., excluding trips between secondary service areas)
		Coord fromCoord = passengerRequest.getFromLink().getToNode().getCoord();
		Coord toCoord = passengerRequest.getToLink().getToNode().getCoord();
		for (Geometry mainServiceArea : mainServiceAreas) {
			if (mainServiceArea.contains(MGC.coord2Point(fromCoord)) || mainServiceArea.contains(MGC.coord2Point(toCoord))) {
				// the trip is valid → return an empty set and no need to continue
				return Collections.emptySet();
			}
		}
		// otherwise, this trip is not allowed → will be rejected by passenger engine
		return Collections.singleton(TRIP_NOT_ALLOWED);
	}
}
