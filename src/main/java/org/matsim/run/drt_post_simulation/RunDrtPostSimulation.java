package org.matsim.run.drt_post_simulation;

import com.google.common.base.Preconditions;
import com.google.inject.Singleton;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.optimizer.constraints.ConstraintSetChooser;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.utils.objectattributes.attributable.Attributes;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter.glob;
import static org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme.serviceAreaBased;

public class RunDrtPostSimulation implements MATSimAppCommand {
	@CommandLine.Option(names = "--config", description = "path to config file", required = true)
	private String configPath;

	@CommandLine.Option(names = "--output", description = "output root directory", required = true)
	private String outputRootDirectory;

	@CommandLine.Option(names = "--fleet-sizing", description = "a triplet: [from max interval]. ", arity = "1..*", defaultValue = "10 30 5")
	private List<Integer> fleetSizing;

	@CommandLine.Option(names = "--target-mean-wait-time", description = "target mean wait time by default, can be overwritten by the values in shp", defaultValue = "600")
	private int defaultTargetMeanWaitTime;

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	public static void main(String[] args) {
		new RunDrtPostSimulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		// Decoding fleet sizing sequence
		Preconditions.checkArgument(fleetSizing.size() == 3);
		int fleetFrom = fleetSizing.get(0);
		int fleetMax = fleetSizing.get(1);
		int fleetInterval = fleetSizing.get(2);

		// read DRT service area for waiting time analysis
		List<SimpleFeature> features = shp.readFeatures();

		// Run simulations and analyze output
		for (int fleetSize = fleetFrom; fleetSize <= fleetMax; fleetSize += fleetInterval) {
			// setup run with specific fleet size
			String outputDirectory = outputRootDirectory + "/" + fleetSize + "-veh";
			Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
			MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
			config.controller().setOutputDirectory(outputDirectory);
			config.global().setCoordinateSystem("EPSG:25832");

			DrtConfigGroup drtCfg = DrtConfigGroup.getSingleModeDrtConfig(config);
			drtCfg.vehiclesFile = "./vehicles/" + fleetSize + "-8_seater-drt-vehicles.xml";
			drtCfg.drtServiceAreaShapeFile = shp.getShapeFile();
			drtCfg.operationalScheme = serviceAreaBased;

			// create various constraint set (i.e., different waiting time constraints)
			DrtOptimizationConstraintsParams drtOptimizationConstraintsParams = drtCfg.addOrGetDrtOptimizationConstraintsParams();
			Set<DrtOptimizationConstraintsSet> drtConstraintSets = createDrtConstraintSetsFromShp(shp.getShapeFile());
			for (DrtOptimizationConstraintsSet drtConstraintSet : drtConstraintSets) {
				drtOptimizationConstraintsParams.addParameterSet(drtConstraintSet);
			}

			Controler controler = DrtControlerCreator.createControler(config, false);
			// Use shape-file-based constraints
			controler.addOverridingModule(new AbstractDvrpModeModule(drtCfg.mode) {
				@Override
				public void install() {
					bindModal(ConstraintSetChooser.class).toProvider(
						() -> new ShpBasedConstraintChooser(shp.getShapeFile(), drtCfg)).in(Singleton.class);
				}
			});
			controler.run();

			// analyze output
			Map<Double, List<Double>> waitTimeGroupingPerTargetWaitTime = new HashMap<>();

			Path customerStats = glob(Path.of(outputDirectory), "*output_drt_legs_drt.csv").orElse(Path.of("file does not exist"));
			try (CSVParser parser = new CSVParser(Files.newBufferedReader(customerStats),
				CSVFormat.Builder.create()
					.setDelimiter(CsvOptions.detectDelimiter(customerStats.toString()))
					.setHeader().setSkipHeaderRecord(true)
					.build())) {
				for (CSVRecord record : parser.getRecords()) {
					Coord fromCoord = new Coord(Double.parseDouble(record.get("fromX")), Double.parseDouble(record.get("fromY")));
					waitTimeGroupingPerTargetWaitTime.computeIfAbsent(getMinTargetMeanWaitTime(features, fromCoord), l -> new ArrayList<>())
						.add(Double.parseDouble(record.get("waitTime")));
				}
				// check if the mean wait time in each group is below the target value
				boolean fleetSizeIsAdequate = true;
				for (Double targetWaitTime : waitTimeGroupingPerTargetWaitTime.keySet()) {
					double actualMeanWaitTime = waitTimeGroupingPerTargetWaitTime.get(targetWaitTime).stream().mapToDouble(v -> v).average().orElse(0.);
					if (actualMeanWaitTime > targetWaitTime) {
						fleetSizeIsAdequate = false;
						break;
					}
				}

				if (fleetSizeIsAdequate) {
					break;
				}
			}
		}
		return 0;
	}

	private double getMinTargetMeanWaitTime(List<SimpleFeature> features, Coord coord) {
		double minTargetMeanWaitTime = defaultTargetMeanWaitTime;
		for (SimpleFeature feature : features) {
			if (MGC.coord2Point(coord).within((Geometry) feature.getDefaultGeometry())) {
				double typicalWaitTime = Double.parseDouble(feature.getAttribute("typ_wt").toString());
				if (typicalWaitTime < minTargetMeanWaitTime) {
					minTargetMeanWaitTime = typicalWaitTime;
				}
			}
		}
		return minTargetMeanWaitTime;
	}

	/**
	 * Shape-file-based constraint chooser will set up different constraints sets for DRT requests based on its start location and the
	 * specified typical waiting time in the shape file.
	 */
	class ShpBasedConstraintChooser implements ConstraintSetChooser {
		private final List<SimpleFeature> features;
		private final Map<Double, DrtOptimizationConstraintsSet> constraintsMap;

		ShpBasedConstraintChooser(String drtOperationalArea, DrtConfigGroup drtConfigGroup) {
			this.features = new ShpOptions(drtOperationalArea, null, null).readFeatures();
			this.constraintsMap = new HashMap<>();
			for (DrtOptimizationConstraintsSet drtOptimizationConstraintsSet : drtConfigGroup.addOrGetDrtOptimizationConstraintsParams().getDrtOptimizationConstraintsSets()) {
				constraintsMap.put(drtOptimizationConstraintsSet.maxWaitTime, drtOptimizationConstraintsSet);
			}
		}

		@Override
		public Optional<DrtOptimizationConstraintsSet> chooseConstraintSet(double departureTime, Link accessActLink, Link egressActLink, Person person, Attributes tripAttributes) {
			double maxWaitTime = defaultTargetMeanWaitTime * 1.5;
			// the factor 1.5 is an initial estimation, may need to be adjusted

			Point fromPoint = MGC.coord2Point(accessActLink.getToNode().getCoord());
			for (SimpleFeature feature : features) {
				if (fromPoint.within((Geometry) feature.getDefaultGeometry())) {
					double typicalWaitTimeInZone = Double.parseDouble(feature.getAttribute("typ_wt").toString());
					if (typicalWaitTimeInZone * 1.5 < maxWaitTime) {
						maxWaitTime = typicalWaitTimeInZone * 1.5;
					}
				}
			}
			return Optional.of(constraintsMap.get(maxWaitTime));
		}
	}

	private Set<DrtOptimizationConstraintsSet> createDrtConstraintSetsFromShp(String drtOperationalArea) {
		Set<DrtOptimizationConstraintsSet> drtConstraintSets = new HashSet<>();
		Set<Double> waitTimes = new HashSet<>();

		// add default constraint set
		drtConstraintSets.add(createDrtOptimizationConstraintsSet(defaultTargetMeanWaitTime * 1.5));
		waitTimes.add(defaultTargetMeanWaitTime * 1.5);

		List<SimpleFeature> features = new ShpOptions(drtOperationalArea, null, null).readFeatures();
		for (SimpleFeature feature : features) {
			double targetWaitTime = Double.parseDouble(feature.getAttribute("typ_wt").toString());
			if (!waitTimes.contains(targetWaitTime * 1.5)){
				drtConstraintSets.add(createDrtOptimizationConstraintsSet(targetWaitTime * 1.5));
				waitTimes.add(targetWaitTime * 1.5);
			}
		}
		return drtConstraintSets;
	}

	private DrtOptimizationConstraintsSet createDrtOptimizationConstraintsSet(double maxWaitTime) {
		DefaultDrtOptimizationConstraintsSet constraintsSet = new DefaultDrtOptimizationConstraintsSet();
		constraintsSet.maxWaitTime = maxWaitTime;
		constraintsSet.maxDetourAlpha = 1.5;
		// 1.5 for maxDetourAlpha is an initial estimation, may need to be adjusted
		constraintsSet.maxDetourBeta = 600;
		// 600 for maxDetourBeta is an initial estimation, may need to be adjusted
		constraintsSet.maxTravelTimeAlpha = 10.;
		constraintsSet.maxTravelTimeBeta = 7200;
		constraintsSet.rejectRequestIfMaxWaitOrTravelTimeViolated = false;
		constraintsSet.maxAllowedPickupDelay = 180;
		constraintsSet.name = "constraint_with_max_wait_time_" + maxWaitTime;
		return constraintsSet;
	}
}
