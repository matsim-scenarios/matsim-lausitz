/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2016 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.utils;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetWriter;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

/**
 * Generating DRT fleets. The starting locations can be determined by shape file, depots or completely randomly.
 */
public class CreateFleetVehicles implements MATSimAppCommand {
	@CommandLine.Option(names = "--network", description = "path to network file", required = true)
	private String networkFile;

	@CommandLine.Option(names = "--fleet-size-from", description = "number of vehicles to generate", required = true)
	private int fleetSizeFrom;

	@CommandLine.Option(names = "--fleet-size-to", description = "number of vehicles to generate", required = true)
	private int fleetSizeTo;

	@CommandLine.Option(names = "--fleet-size-interval", description = "number of vehicles to generate", defaultValue = "10")
	private int fleetSizeInterval;

	@CommandLine.Option(names = "--capacity", description = "capacity of the vehicle", required = true)
	private int capacity;

	@CommandLine.Option(names = "--output-folder", description = "path to output folder", required = true)
	private Path outputFolder;

	@CommandLine.Option(names = "--operator", description = "name of the operator", defaultValue = "drt")
	private String operator;

	@CommandLine.Option(names = "--start-time", description = "service starting time", defaultValue = "0")
	private double startTime;

	@CommandLine.Option(names = "--end-time", description = "service ending time", defaultValue = "108000")
	private double endTime;

	@CommandLine.Option(names = "--depots", description = "Path to the depots location file", defaultValue = "")
	private String depotsPath;

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	private static final Logger log = LogManager.getLogger(CreateFleetVehicles.class);
	private static final SplittableRandom random = new SplittableRandom(1);
	static final String SEAT = "_seater-";

	public CreateFleetVehicles(int capacity, String operator, double startTime, double endTime, String depotsPath, ShpOptions shp, String networkFile, Path outputFolder) {
		this.capacity = capacity;
		this.operator = operator;
		this.startTime = startTime;
		this.endTime = endTime;
		this.depotsPath = depotsPath;
		this.shp = shp;
		this.networkFile = networkFile;
		this.outputFolder = outputFolder;
	}

	private CreateFleetVehicles() {

	}

	public static void main(String[] args) {
		new CreateFleetVehicles().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		if (!Files.exists(outputFolder)) {
			Files.createDirectory(outputFolder);
		}
		List<Link> links = getAllowedStartLinks();

		for (int fleetSize = fleetSizeFrom; fleetSize <= fleetSizeTo; fleetSize += fleetSizeInterval) {
			generateFleetWithSpecifiedParams(fleetSize, links, null, TransportMode.drt);
		}
		return 0;
	}

	/**
	 * Get allowed start links for drt vehicles based on shp or depots file.
	 */
	public @NotNull List<Link> getAllowedStartLinks() throws IOException {
		Network network = NetworkUtils.readNetwork(networkFile);

		List<Link> links = network.getLinks().values().stream().filter(l -> l.getAllowedModes().contains(TransportMode.car)).collect(Collectors.toList());
		if (shp.isDefined()) {
			Geometry serviceArea = shp.getGeometry();
			links = links.stream().filter(l -> MGC.coord2Point(l.getToNode().getCoord()).within(serviceArea)).collect(Collectors.toList());
		} else if (!depotsPath.isEmpty()) {
			try (CSVParser parser = new CSVParser(Files.newBufferedReader(Path.of(depotsPath), StandardCharsets.UTF_8), CSVFormat.DEFAULT.withDelimiter(',').withFirstRecordAsHeader())) {
				links.clear();
				for (CSVRecord csvRecord : parser) {
					Link depotLink = network.getLinks().get(Id.createLinkId(csvRecord.get(0)));
					links.add(depotLink);
				}
			}
		} else {
			throw new IllegalArgumentException("Neither a drt service area shp file nor a depots file are specified!");
		}
		return links;
	}

	/**
	 * Method to generate a drt vehicle fleet with specified params.
	 */
	public String[] generateFleetWithSpecifiedParams(int fleetSize, List<Link> allowedStartLinks, String runId, String networkMode) {
		log.info("Creating fleet with size {}", fleetSize);
		List<DvrpVehicleSpecification> vehicleSpecifications = new ArrayList<>();
		for (int i = 0; i < fleetSize; i++) {
			Id<Link> startLinkId;
			if (depotsPath.isEmpty()) {
				startLinkId = allowedStartLinks.get(random.nextInt(allowedStartLinks.size())).getId();
			} else {
				startLinkId = allowedStartLinks.get(i % allowedStartLinks.size()).getId();
				// Even distribution of the vehicles
			}
			DvrpVehicleSpecification vehicleSpecification = ImmutableDvrpVehicleSpecification.newBuilder()
				.id(Id.create(operator + "_" + i, DvrpVehicle.class))
				.startLinkId(startLinkId)
				.capacity(capacity)
				.serviceBeginTime(startTime)
				.serviceEndTime(endTime)
				.build();
			vehicleSpecifications.add(vehicleSpecification);
		}
		String outputPath = runId != null ? outputFolder.resolve(runId + "." + fleetSize + "-" + capacity + SEAT + operator + "-vehicles.xml").toString() :
			outputFolder.resolve(fleetSize + "-" + capacity + SEAT + operator + "-vehicles.xml").toString();
		new FleetWriter(vehicleSpecifications.stream()).write(outputPath);
		log.info("Drt fleet with size of {} vehicles and single vehicle capacity of {} written to {}", vehicleSpecifications.size(), capacity, outputPath);

		String outputVehTypesPath = runId != null ? outputFolder.resolve(runId + "." + capacity + SEAT + operator + "-vehicle-types.xml").toString() :
			outputFolder.resolve(capacity + SEAT + operator + "-vehicle-types.xml").toString();

		if (!Files.exists(Path.of(outputVehTypesPath))) {
			Vehicles vehicles = VehicleUtils.createVehiclesContainer();
			VehicleType type = VehicleUtils.createVehicleType(Id.create(networkMode, VehicleType.class));
			type.setNetworkMode(networkMode);
			type.getCapacity().setSeats(capacity);
			vehicles.addVehicleType(type);
			new MatsimVehicleWriter(vehicles).writeFile(outputVehTypesPath);
		}

		return new String[]{outputPath, outputVehTypesPath};
	}
}
