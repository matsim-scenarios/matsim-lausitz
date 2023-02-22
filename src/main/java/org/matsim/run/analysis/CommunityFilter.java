package org.matsim.run.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(name = "community-filter", description = "creates a csv with commuity keys within shape")
public class CommunityFilter implements MATSimAppCommand {

	@CommandLine.Option(names = "--community-shp", description = "path to VG5000_GEM.shp", required = true)
	private static Path communityShapePath;

	@CommandLine.Option(names = "--dilution-area", description = "path to area-of-interest-shape file", required = true)
	private static Path dilutionAreaShapePath;

	@CommandLine.Option(names = "--output", description = "output csv filepath", required = true)
	private static Path output;

	@CommandLine.Mixin
	CsvOptions csvOptions = new CsvOptions();

	private final List<String> keys = new ArrayList<>();
	private final Map<String, Tuple<Double, Double>> filtered = new HashMap<>();

	public static void main(String[] args) {
		new CommunityFilter().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Collection<SimpleFeature> communities = readShapeFile(communityShapePath);
		Collection<SimpleFeature> dilutionArea = readShapeFile(dilutionAreaShapePath);

		List<Geometry> geometries = dilutionArea.stream().map(feature -> (Geometry) feature.getDefaultGeometry()).collect(Collectors.toList());

		for(var community: communities){

			String attribute = (String) community.getAttribute("ARS");

			if(geometries.stream()
					.anyMatch(geometry -> geometry.covers((Geometry) community.getDefaultGeometry()))){

				Point centroid = ((Geometry) community.getDefaultGeometry()).getCentroid();
				Tuple<Double, Double> coordinates = Tuple.of(centroid.getX(), centroid.getY());

				filtered.put(attribute, coordinates);
			}

		}

		CSVPrinter printer = csvOptions.createPrinter(output);
		printer.print("ars");
		printer.print("x");
		printer.print("y");
		printer.println();

		for(Map.Entry<String, Tuple<Double, Double>> entry: filtered.entrySet()){
			printer.print(entry.getKey());
			printer.print(entry.getValue().getFirst());
			printer.print(entry.getValue().getSecond());
			printer.println();
		}
		printer.close();

		return 0;
	}

	private static Collection<SimpleFeature> readShapeFile(Path filepath){

		return ShapeFileReader.getAllFeatures(filepath.toString());
	}
}
