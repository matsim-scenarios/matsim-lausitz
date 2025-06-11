package org.matsim.utils;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.counts.Counts;
import org.matsim.counts.MatsimCountsReader;
import picocli.CommandLine;

import java.util.HashSet;
import java.util.Set;

@CommandLine.Command(
	name = "extract-count-locations",
	description = "Helper class for extracting coords for count stations based on their assigned network links."
)
public class ExtractCountLocations implements MATSimAppCommand {
	@CommandLine.Option(names = "--counts", description = "Path to xml file with count data.", required = true)
	private String countsPath;
	@CommandLine.Option(names = "--network", description = "Path to xml file with network.", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--output", description = "Path for output count locations. Should be csv.", required = true)
	private String output;

	public static void main(String[] args) {
		new ExtractCountLocations().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Set<LocationRecord> locations = new HashSet<>();

		Counts<Link> counts = new Counts<>();
		new MatsimCountsReader(counts).readFile(countsPath);

		Network network = NetworkUtils.readNetwork(networkPath);

		counts.getMeasureLocations().values()
			.forEach(m -> locations.add(new LocationRecord(m.getStationName(), network.getLinks().get(m.getRefId()).getCoord(), m.getRefId(),
				network.getLinks().get(m.getRefId()).getAttributes().getAttribute("type").toString())));

		try (CSVPrinter printer = new CSVPrinter(IOUtils.getBufferedWriter(output), CSVFormat.DEFAULT)) {
			printer.printRecord("location_name", "x", "y", "linkId", "linkType");

			for (LocationRecord locationRecord : locations) {
				printer.printRecord(locationRecord.name, locationRecord.coord.getX(), locationRecord.coord.getY(), locationRecord.linkId, locationRecord.linkType);
			}
		}
		return 0;
	}

	private record LocationRecord(String name, Coord coord, Id<Link> linkId, String linkType) {}
}
