package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.vehicles.VehicleType;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Run the Lausitz scenario with bike as teleported mode.
 * All necessary configs will be made in this class.
 */
public class LausitzBikeTeleportedScenario extends LausitzScenario {
	Logger log = LogManager.getLogger(LausitzBikeTeleportedScenario.class);

	@Nullable
	@Override
	public Config prepareConfig(Config config) {
//		//		apply all config changes from base scenario class
		super.prepareConfig(config);

//		remove bike from qsim main modes
		Set<String> mainModes = new HashSet<>(config.qsim().getMainModes());
		mainModes.remove(TransportMode.bike);
		config.qsim().setMainModes(mainModes);
		log.info("Removed bike as qsim main mode. Bike is not simulated on the network.");


		RoutingConfigGroup routingConfigGroup = ConfigUtils.addOrGetModule(config, RoutingConfigGroup.class);

//			remove bike as routed (on network) mode
		Set<String> networkModes = new HashSet<>(routingConfigGroup.getNetworkModes());
		networkModes.remove(TransportMode.bike);
		routingConfigGroup.setNetworkModes(networkModes);
		log.info("Removed bike as network mode. Bike is not routed on the network.");

//			add teleported mode params for bike
		RoutingConfigGroup.TeleportedModeParams bikeParams = new RoutingConfigGroup.TeleportedModeParams(TransportMode.bike);
		bikeParams.setBeelineDistanceFactor(1.3);
//			according to v6.4 vehicle types file the reported bike speed in SrV is 10.29km/h
		double bikeTeleportedSpeed = BigDecimal
			.valueOf(10.29 / 3.6)
			.setScale(2, RoundingMode.HALF_UP)
			.doubleValue();
		bikeParams.setTeleportedModeSpeed(bikeTeleportedSpeed);
		routingConfigGroup.addTeleportedModeParams(bikeParams);
		log.info("Added teleported mode params for bike with teleportedModeSpeed {}.", bikeTeleportedSpeed);

		return config;
	}

	@Override
	public void prepareScenario(Scenario scenario) {
		//		apply all scenario changes from base scenario class
		super.prepareScenario(scenario);

		//			remove bike veh type
		scenario.getVehicles().removeVehicleType(Id.create(TransportMode.bike, VehicleType.class));
		log.info("Removed vehicle type for bike.");
	}

	@Override
	public void prepareControler(Controler controler) {
		//		apply all controller changes from base scenario class
		super.prepareControler(controler);
	}
}
