package org.matsim.run.analysis;

import org.apache.commons.csv.CSVPrinter;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.run.scenarios.LausitzScenario;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@CommandLine.Command(name = "od-analysis", description = "Analyze individual od relations")
public class GeneralOdAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--population", description = "Input population", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output csv", required = true)
	private Path output;

	@CommandLine.Option(names = "--min-dist", description = "The minimum euclidean distance for trips to be considered", defaultValue = "500")
	private double minDistance;

	@CommandLine.Option(names = "--intermediate-activities", description = "Switch on/off the analysis of intermediate acitivity locations for trips." +
		"If switched on, the analysis will check if there are transfers in the given area.", defaultValue = "DISABLED")
	private LausitzScenario.FunctionalityHandling intermediateActivities;

	@CommandLine.Mixin
	private CsvOptions csv;

	@CommandLine.Mixin
	private ShpOptions shp;

	public static void main(String[] args) {
		new GeneralOdAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		MainModeIdentifier modeIdentifier = new DefaultAnalysisMainModeIdentifier();
		Population population = PopulationUtils.readPopulation(input.toString());

		List<SimpleFeature> features = shp.isDefined() ? shp.readFeatures() : null;

		try (CSVPrinter printer = csv.createPrinter(output)) {
			printer.printRecord("from_x", "from_y", "to_x", "to_y", "departure_time", "main_mode", "from_area", "to_area", "between_act_area", "between_act_x", "between_act_y", "between_act_end_time");
			for (Person person : population.getPersons().values()) {
				Plan selectedPlan = person.getSelectedPlan();
				List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(selectedPlan);
				for (TripStructureUtils.Trip trip : trips) {
					String mode = modeIdentifier.identifyMainMode(trip.getTripElements());
					// there is currently no TransportMode.freight or TransportMode.longDistanceFreight or TransportMode.truck8t ...
					if (mode.equals("longDistanceFreight") || mode.equals("truck8t") || mode.equals("truck18t") || mode.equals("truck40t")){
						continue;
					}
					Coord fromCoord = trip.getOriginActivity().getCoord();
					Coord toCoord = trip.getDestinationActivity().getCoord();
					Activity originActivity = trip.getOriginActivity();
					double departureTime = originActivity.getEndTime().orElse(-1);

					String startArea = determineInsideArea(fromCoord, features);
					String endArea = determineInsideArea(toCoord, features);
					String betweenArea = null;

					AtomicReference<Activity> act = new AtomicReference<>();
					AtomicReference<String> intermediateArea = new AtomicReference<>();

					if (intermediateActivities == LausitzScenario.FunctionalityHandling.ENABLED) {
//						we are always interested in the last intermediate = interaction act
//						e.g. case: agent travels from hoyerswerda to cottbus by train
//						starts from somewhere in hoy and has pt interaction in hoy main station
//						also has pt interaction in cott main station before travelling to final dest in cott
//						we want to know about the interaction act closest to destination

//						filter for acts in trip, filter for acts in area, continuously update act
//						trip.getTripElements() does not contain the start and end act!
						trip.getTripElements().stream()
							.filter(Activity.class::isInstance)
							.map(e -> (Activity) e)
							.filter(a -> determineInsideArea(a.getCoord(), features) != null)
							.forEach(activity -> {
								act.set(activity);
								intermediateArea.set(determineInsideArea(activity.getCoord(), features));
							});

						if (act.get() != null) {
							betweenArea = intermediateArea.get();
						}
					}

					if (CoordUtils.calcEuclideanDistance(fromCoord, toCoord) > minDistance &&
						(features == null || startArea != null || endArea != null)) {
						if (betweenArea != null) {
							printer.printRecord(
								Double.toString(fromCoord.getX()),
								Double.toString(fromCoord.getY()),
								Double.toString(toCoord.getX()),
								Double.toString(toCoord.getY()),
								Double.toString(departureTime),
								mode,
								startArea,
								endArea,
								betweenArea,
								act.get().getCoord().getX(),
								act.get().getCoord().getY(),
								act.get().getEndTime()
								);
						} else {
							printer.printRecord(
								Double.toString(fromCoord.getX()),
								Double.toString(fromCoord.getY()),
								Double.toString(toCoord.getX()),
								Double.toString(toCoord.getY()),
								Double.toString(departureTime),
								mode,
								startArea,
								endArea,
								betweenArea,
								null,
								null,
								null
							);
						}
					}
				}
			}
		}
		return 0;
	}

	private String determineInsideArea(Coord coord, List<SimpleFeature> features) {
		String areaName = null;
		double surfaceArea = 0.;

		for (SimpleFeature feature : features) {
			if (surfaceArea == 0.) {
				if (MGC.coord2Point(coord).within((Geometry) feature.getDefaultGeometry())) {
					areaName = feature.getAttribute("name").toString();
					surfaceArea = ((Geometry) feature.getDefaultGeometry()).getArea();
				}
			} else {
				double anotherSurfaceArea = ((Geometry) feature.getDefaultGeometry()).getArea();

//					if in smaller area (= area with no buffer e.g.) overwrite areaName
				if (MGC.coord2Point(coord).within((Geometry) feature.getDefaultGeometry()) &&
					anotherSurfaceArea < surfaceArea) {
					areaName = feature.getAttribute("name").toString();
					surfaceArea = anotherSurfaceArea;
				}
			}
		}
		return areaName;
	}
}
