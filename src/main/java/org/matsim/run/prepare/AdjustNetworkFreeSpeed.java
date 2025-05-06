package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

@CommandLine.Command(
	name = "network",
	description = "Adjust networks free speed."
)

public class AdjustNetworkFreeSpeed implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AdjustNetworkFreeSpeed.class);
	@CommandLine.Option(names = "--networkPath", description = "Path to network file", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--output", description = "Output path of the prepared network", required = true)
	private String outputPath;
	@CommandLine.Option(names = "--adjustMotorway", description = "Boolean to declare if motorway is to be adjusted", required = true)
	private boolean adjustMotorway;
	@CommandLine.Option(names = "--motorwayFactor", description = "Factor the Motorway free speed should be adjusted to", required = false)
	private float motorwayFactor;
	@CommandLine.Option(names = "--adjustNonMotorway", description = "Boolean to declare if motorway is to be adjusted", required = true)
	private boolean adjustNonMotorway;
	@CommandLine.Option(names = "--nonMotorwayFactor", description = "Factor the nonMotorway free speed should be adjusted to", required = false)
	private float nonMotorwayFactor;
	@CommandLine.Option(names = "--currentFreeSpeedFactor", description = "The current factor the free speed is scaled down by from sumo network", required = false)
	private float currentFreeSpeedFactor;
	public static void main(String[] args) {
		new AdjustNetworkFreeSpeed().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Network network = NetworkUtils.readNetwork(networkPath);
		if(adjustMotorway && adjustNonMotorway) {
			adjustMotorwayFreeSpeed(network, motorwayFactor, currentFreeSpeedFactor);
			adjustNonMotorwayFreeSpeed(network, nonMotorwayFactor, currentFreeSpeedFactor);
		}
		if(adjustNonMotorway && !adjustMotorway) {
			adjustNonMotorwayFreeSpeed(network, nonMotorwayFactor, currentFreeSpeedFactor);
		}
		if(adjustMotorway & !adjustNonMotorway) {
			adjustMotorwayFreeSpeed(network, motorwayFactor, currentFreeSpeedFactor);
		}

		NetworkUtils.writeNetwork(network, outputPath);
		return 0;
	}

	/**
	 * adjust free speed for all links of all types except motorway.
	 */


	public static void adjustMotorwayFreeSpeed(Network network, float motorwayFactor, float currentFreeSpeedFactor) {
		for (Link link : network.getLinks().values()) {
			// ignore pt Links !
			if(link.getId().toString().startsWith("pt_")){
				continue;
			}

			if (link.getAttributes().getAttribute("type").toString().startsWith("highway.motorway")) {
				double currentSpeed = link.getFreespeed();
				//log.info("the current free speed of link {} which is of type {} is {} km/h", link.getId().toString(), link.getAttributes().getAttribute("type"), currentSpeed);
				double originalSpeed = (currentSpeed / currentFreeSpeedFactor);
				//log.info("the original free speed of link {} is {} km/h", link.getId().toString(), originalSpeed);
				double newSpeed = originalSpeed * motorwayFactor;
				link.setFreespeed(newSpeed);
				log.info("For link {} the free speed has been adjusted to {} km/h", link.getId().toString(), newSpeed);
				link.getAttributes().putAttribute("allowed_speed", newSpeed);

			}
		}



	}
	/**
	 * adjust free speed for all links of type motorway.
	 */

	public static void adjustNonMotorwayFreeSpeed(Network network, float nonMotorwayFactor, float currentFreeSpeedFactor) {
		// ignore pt Links !
		for (Link link : network.getLinks().values()) {
			if(link.getId().toString().startsWith("pt_")){
				continue;
			}
			if (link.getAttributes().getAttribute("type").toString().startsWith("highway.motorway")) {
	 			continue;
			} else {
				double currentSpeed = link.getFreespeed();
				//log.info("the current free speed of link {} which is of type {} is {} km/h", link.getId().toString(), link.getAttributes().getAttribute("type"), currentSpeed);
				double originalSpeed = (currentSpeed / currentFreeSpeedFactor);
				//log.info("the original free speed of link {} is {} km/h", link.getId().toString(), originalSpeed);
				double newSpeed = originalSpeed * nonMotorwayFactor;
				link.setFreespeed(newSpeed);
				log.info("For link {} the free speed has been adjusted to {} km/h", link.getId().toString(), newSpeed);
				link.getAttributes().putAttribute("allowed_speed", newSpeed);
			}
		}
	}

}
