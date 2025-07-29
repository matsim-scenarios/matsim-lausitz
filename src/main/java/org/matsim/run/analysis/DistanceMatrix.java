package org.matsim.run.analysis;

import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.GeoFileReader;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@CommandLine.Command(name = "distance-matrix", description = "creates a csv with commuity keys within shape")
public class DistanceMatrix implements MATSimAppCommand{

	@CommandLine.Option(names = "--output", description = "output csv filepath", required = true)
	private static Path output;

	@CommandLine.Option(names = "--dilution-area", description = "shape to filter zones", required = false)
	private static Path dilutionArea;

	@CommandLine.Mixin
	CsvOptions csvOptions = new CsvOptions();

	@CommandLine.Mixin
	ShpOptions shp = new ShpOptions();

	private final List<String> distances = new ArrayList<>();
	private static final Logger logger = LogManager.getLogger(DistanceMatrix.class);

	public static void main(String[] args) {
		new DistanceMatrix().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		logger.info("Read features.");
		List<SimpleFeature> communities = shp.readFeatures();

		//to prevent RuntimeExceptions
		ArrayList<SimpleFeature> copy = new ArrayList<>(communities);

		Predicate<Geometry> filter = getFilter(dilutionArea);

		logger.info("Calculate distance matrix.");
		String delimiter = csvOptions.getFormat().getDelimiterString();
		for(var community: communities){

			if(!filter.test((Geometry) community.getDefaultGeometry()))
				continue;
			String nameFrom = (String) community.getAttribute("ARS");
			Point centroid = ((Geometry) community.getDefaultGeometry()).getCentroid();
			Coord from = MGC.point2Coord(centroid);
			for(var target: copy){

				String nameTo = (String) target.getAttribute("ARS");

				Point centroid2 = ((Geometry) target.getDefaultGeometry()).getCentroid();
				Coord to = MGC.point2Coord(centroid2);
				double distance = CoordUtils.calcEuclideanDistance(from, to);

				String distanceString = String.valueOf(distance).replace('.', ',');

				distances.add(nameFrom + delimiter + nameTo + delimiter + distanceString);
			}
		}

		logger.info("Print results to {}", output);
		try (CSVPrinter printer = csvOptions.createPrinter(output)) {
			printer.print("from");
			printer.print("to");
			printer.print("distance");
			printer.println();

			for (String entry : distances) {
				for (String col : entry.split(csvOptions.getFormat().getDelimiterString()))
					printer.print(col);
				printer.println();
			}
		}

		logger.info("Done!");
		return 0;
	}

	private Predicate<Geometry> getFilter(Path path){

		if(path == null)
			return community -> true;

		List<Geometry> geometries = GeoFileReader.getAllFeatures(path.toString()).stream()
				.map(feature -> (Geometry) feature.getDefaultGeometry())
				.toList();

		return community -> geometries.stream().anyMatch(geometry -> geometry.covers(community));
	}
}
