package org.matsim.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lausitz scenario including speed reductions on network links of all types except motorways.
 * Speed reduction can be done relatively or by absolute value.
 * All necessary configs will be made in this class.
 */
public class LausitzSpeedReductionScenario extends LausitzScenario {
	Logger log = LogManager.getLogger(LausitzSpeedReductionScenario.class);

	private final LausitzScenario baseScenario = new LausitzScenario(sample, emissions);

	@CommandLine.Option(names = "--slow-speed-shp", description = "Path to shp file for adaption of link speeds.", defaultValue = "../shp/lausitz.shp")
	private String slowSpeedAreaShp;
	@CommandLine.Option(names = "--slow-speed-relative-change", description = "provide a value that is bigger than 0.0 and smaller than 1.0." +
		"The default is set to 0.6, such that roads with an allowed speed of 50kmh are reduced to 30kmh.", defaultValue = "0.6")
	private double relativeSpeedChange;

	public LausitzSpeedReductionScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzSpeedReductionScenario(@Nullable String args) {
		super(args);
	}

	public LausitzSpeedReductionScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		//		apply all config changes from base scenario class
		baseScenario.prepareConfig(config);
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		//		apply all scenario changes from base scenario class
		baseScenario.prepareScenario(scenario);

		List<PreparedGeometry> geometries = ShpGeometryUtils.loadPreparedGeometries(IOUtils.extendUrl(scenario.getConfig().getContext(), slowSpeedAreaShp));

		Set<? extends Link> carLinksInArea = scenario.getNetwork().getLinks().values().stream()
			//filter car links
			.filter(link -> link.getAllowedModes().contains(TransportMode.car))
			//spatial filter
			.filter(link -> ShpGeometryUtils.isCoordInPreparedGeometries(link.getCoord(), geometries))
			//we won't change motorways and motorway_links
			.filter(link -> !((String) link.getAttributes().getAttribute("type")).contains("motorway"))
			.collect(Collectors.toSet());

		if (relativeSpeedChange >= 0.0 && relativeSpeedChange < 1.0) {
			log.info("reduce speed relatively by a factor of: {}", relativeSpeedChange);

			//apply speed reduction to all roads but motorways
			carLinksInArea.forEach(link -> link.setFreespeed(link.getFreespeed() * relativeSpeedChange));
		} else {
			log.fatal("Speed reduction value of {} is invalid. Please put a 0.0 <= value < 1.0", relativeSpeedChange);
			throw new IllegalArgumentException();
		}
	}

	@Override
	protected void prepareControler(Controler controler) {
		//		apply all controller changes from base scenario class
		baseScenario.prepareControler(controler);
	}
}
