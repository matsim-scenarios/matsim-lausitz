package org.matsim.run.analysis;

import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(name = "od-analysis", description = "Analyze individual od relations")
public class GeneralOdAnalysis implements MATSimAppCommand {
	@CommandLine.Option(names = "--population", description = "Input population", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output csv", required = true)
	private Path output;

	@CommandLine.Option(names = "--min-dist", description = "The minimum euclidean distance for trips to be considered", defaultValue = "500")
	private double minDistance;

	@CommandLine.Mixin
	private CsvOptions csv;

	@CommandLine.Mixin
	private ShpOptions shp;

//	@CommandLine.Mixin
//	private CrsOptions crs;

	public static void main(String[] args) {
		new GeneralOdAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		MainModeIdentifier modeIdentifier = new DefaultAnalysisMainModeIdentifier();
		Population population = PopulationUtils.readPopulation(input.toString());
		Geometry serviceArea = shp.getGeometry();

		try (CSVPrinter printer = csv.createPrinter(output)) {
			printer.printRecord("from_x", "from_y", "to_x", "to_y", "departure_time");
			for (Person person : population.getPersons().values()) {
				Plan selectedPlan = person.getSelectedPlan();
				List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(selectedPlan);
				for (TripStructureUtils.Trip trip : trips) {
					Coord fromCoord = trip.getOriginActivity().getCoord();
					Coord toCoord = trip.getDestinationActivity().getCoord();
					double departureTime = trip.getOriginActivity().getEndTime().orElse(-1.0);

					if (CoordUtils.calcEuclideanDistance(fromCoord, toCoord) > minDistance) {
						if (serviceArea == null || MGC.coord2Point(fromCoord).within(serviceArea) || MGC.coord2Point(toCoord).within(serviceArea)) {
							printer.printRecord(
								Double.toString(fromCoord.getX()),
								Double.toString(fromCoord.getY()),
								Double.toString(toCoord.getX()),
								Double.toString(toCoord.getY()),
								Double.toString(departureTime)
							);
						}
					}
				}
			}
		}
		return 0;
	}
}
