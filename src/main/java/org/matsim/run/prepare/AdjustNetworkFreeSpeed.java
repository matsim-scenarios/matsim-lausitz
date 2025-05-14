package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(
	name = "network",
	description = "Adjust free speed of network links."
)

public class AdjustNetworkFreeSpeed implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AdjustNetworkFreeSpeed.class);

	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private String networkPath;
	@CommandLine.Option(names = "--output", description = "Output path of the prepared network", required = true)
	private String outputPath;
	@CommandLine.Option(names = "--motorway-factor", description = "Factor which is applied to the free speed of motorway links. The default value is the current factor = no change.", defaultValue = "0.75")
	private double motorwayFactor;
	@CommandLine.Option(names = "--non-motorway-factor", description = "Factor which is applied to the free speed of non-motorway links. The default value is the current factor = no change.", defaultValue = "0.75")
	private double nonMotorwayFactor;
	@CommandLine.Option(names = "--current-factor", description = "The current free speed factor for all network links which is applied when converting the sumo network to a MATSim network.", defaultValue = "0.75")
	private float currentFreeSpeedFactor;
	public static void main(String[] args) {
		new AdjustNetworkFreeSpeed().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Network network = NetworkUtils.readNetwork(networkPath);

		AtomicInteger motorwayCount = new AtomicInteger();
		AtomicInteger nonMotorwayCount = new AtomicInteger();

		network.getLinks().values().stream()
			.filter(l -> !l.getId().toString().startsWith("pt_"))
			.forEach(l -> {
				if (l.getAttributes().getAttribute("type").toString().contains("motorway")) {
//					apply motorway factor to motorways
//					we need to calculate the freespeed assigned by osm/sumo before applying the factor: l.getFreespeed() / currentFreeSpeedFactor
					l.setFreespeed(BigDecimal.valueOf(l.getFreespeed() / currentFreeSpeedFactor * motorwayFactor).setScale(2, RoundingMode.HALF_UP).doubleValue());
					motorwayCount.getAndIncrement();
				} else {
//					apply nonMotorway factor to non motorways
					l.setFreespeed(BigDecimal.valueOf(l.getFreespeed() / currentFreeSpeedFactor * nonMotorwayFactor).setScale(2, RoundingMode.HALF_UP).doubleValue());
					nonMotorwayCount.getAndIncrement();
				}
			});
		log.info("For {} motorway links the free speed has been adapted with a factor of {}.", motorwayCount.get(), motorwayFactor);
		log.info("For {} non-motorway links the free speed has been adapted with a factor of {}.", nonMotorwayCount.get(), nonMotorwayFactor);

		outputPath = outputPath.split(".xml.gz")[0] + "-motorway-" + motorwayFactor + "-other-" + nonMotorwayFactor + ".xml.gz";

		NetworkUtils.writeNetwork(network, outputPath);
		log.info("Network with adapted free speeds for motorways with factor {} and non-motorways with factor {} written to {}", motorwayFactor, nonMotorwayFactor, outputPath);
		return 0;
	}
}
