package org.matsim.run.drtpostsimulation;

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
import org.matsim.utils.CreateFleetVehicles;
import org.matsim.utils.objectattributes.attributable.Attributes;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter.glob;
import static org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme.serviceAreaBased;
import static org.matsim.run.scenarios.LausitzScenario.SLASH;

/**
 * Run DRT post-simulation to acquire KPIs of DRT operations.
 */
public class RunDrtPostSimulation implements MATSimAppCommand {
	@CommandLine.Option(names = "--config", description = "path to drt config file", required = true)
	private String configPath;
//	@CommandLine.Option(names = "--drt-plans", description = "path to drt plans file (complete path)", defaultValue = "")
//	private String drtPlansPath;
	@CommandLine.Option(names = "--main-sim-output", description = "path to the output folder of main simulation (complete path)", defaultValue = "")
	private Path mainSimOutputPath;
//	@CommandLine.Option(names = "--output", description = "output root directory", required = true)
//	private String outputRootDirectory;
	@CommandLine.Option(names = "--fleet-sizing", description = "a triplet: [from max interval]. ", arity = "1..*", defaultValue = "10 30 5")
	private List<Integer> fleetSizing;
	@CommandLine.Option(names = "--capacity", description = "vehicle capacity", defaultValue = "8")
	private String vehicleCapacity;
	@CommandLine.Option(names = "--target-mean-wait-time", description = "target mean wait time by default, can be overwritten by the values in shp", defaultValue = "600")
	private int defaultTargetMeanWaitTime;
	@CommandLine.Option(names = "--start-time", description = "service starting time", defaultValue = "0")
	private double startTime;
	@CommandLine.Option(names = "--end-time", description = "service ending time", defaultValue = "108000")
	private double endTime;
	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	private static final String TYP_WT = "typ_wt";
	private static final String POST_SIM_DIR = "drt-post-simulation";

	public static void main(String[] args) {
		new RunDrtPostSimulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {
//		// check if drt plans has been specified
//		if (drtPlansPath.isEmpty()){
//			// if not, we extract drt plans from the main run folder and write it to the root output folder
//			if (mainSimOutputPath == null){
//				throw new IllegalArgumentException("Please specify the drt plans file or the output folder of the main simulation run!!!");
//			}
//			Path drtPlansPath = Path.of(mainSimOutputPath + POST_SIM_DIR + "/drt-plans.xml.gz");
//			new ExtractDrtTrips().execute("--run-folder", mainSimOutputPath.toString(), "--output", drtPlansPath);
//		}

//		extract drt plans if they do no exist
		Path drtPlansPath = Path.of(mainSimOutputPath + POST_SIM_DIR + "/drt-plans.xml.gz");
		if (!Files.exists(drtPlansPath)) {
//			make sure all parent dirs exist
			Files.createDirectories(drtPlansPath.getParent());
			new ExtractDrtTrips().execute("--run-folder", mainSimOutputPath.toString(), "--output", drtPlansPath.toString());
		}

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
			String outputDirectory = drtPlansPath.getParent() + SLASH + fleetSize + "-veh";
//			TODO: rather use output config from run with correct settings and delete stuff we do not need.
			Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
			config.controller().setOutputDirectory(outputDirectory);
			config.global().setCoordinateSystem("EPSG:25832");
			config.plans().setInputFile(drtPlansPath.toString());

			DrtConfigGroup drtCfg = DrtConfigGroup.getSingleModeDrtConfig(config);

//			TODO: clear all replanning strategies except ChangeExpBeta
//			TODO: clear all mode params except drt (and walk?)
//			TODO: clear all act params except interaction acts

			//		define CreateFleetVehicles object to generate drt veh fleet
			CreateFleetVehicles fleetGenerator = new CreateFleetVehicles(Integer.parseInt(vehicleCapacity), drtCfg.getMode(), startTime,
				endTime, "", shp, config.network().getInputFile(), mainSimOutputPath.getParent());

//			TODO: test the auto creation of fleet vehicles.
			drtCfg.vehiclesFile = fleetGenerator.generateFleetWithSpecifiedParams(fleetSize, fleetGenerator.getAllowedStartLinks());
			drtCfg.drtServiceAreaShapeFile = shp.getShapeFile();
			drtCfg.operationalScheme = serviceAreaBased;

			// create various constraint set (i.e., different waiting time constraints)
			DrtOptimizationConstraintsParams drtOptimizationConstraintsParams = drtCfg.addOrGetDrtOptimizationConstraintsParams();

			// Chengqi, April 2025: a default constraint set will always be loaded or created by DrtModeOptimizerQSimModule.
			// If we do not specify it here, it will create a default parameter with rejectRequestIfMaxWaitOrTravelTimeViolated = true.
			// Then rejections will happen, even though our custom constraint sets have rejectRequestIfMaxWaitOrTravelTimeViolated = false.
			// This is because the permission for rejection is based on the default constraint set.
			// I have already suggested moving rejectRequestIfMaxWaitOrTravelTimeViolated to one level up, and then it will be more elegant.
			DrtOptimizationConstraintsSet defaultConstraintSet = drtOptimizationConstraintsParams.addOrGetDefaultDrtOptimizationConstraintsSet();
			defaultConstraintSet.rejectRequestIfMaxWaitOrTravelTimeViolated = false;

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
				for (CSVRecord csvRecord : parser.getRecords()) {
					Coord fromCoord = new Coord(Double.parseDouble(csvRecord.get("fromX")), Double.parseDouble(csvRecord.get("fromY")));
					waitTimeGroupingPerTargetWaitTime.computeIfAbsent(getMinTargetMeanWaitTime(features, fromCoord), l -> new ArrayList<>())
						.add(Double.parseDouble(csvRecord.get("waitTime")));
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
				double typicalWaitTime = Double.parseDouble(feature.getAttribute(TYP_WT).toString());
				if (typicalWaitTime < minTargetMeanWaitTime) {
					minTargetMeanWaitTime = typicalWaitTime;
				}
			}
		}
		return minTargetMeanWaitTime;
	}

	private Set<DrtOptimizationConstraintsSet> createDrtConstraintSetsFromShp(String drtOperationalArea) {
		Set<DrtOptimizationConstraintsSet> drtConstraintSets = new HashSet<>();
		Set<Double> waitTimes = new HashSet<>();

		// add default constraint set
		drtConstraintSets.add(createDrtOptimizationConstraintsSet(defaultTargetMeanWaitTime * 1.5));
		waitTimes.add(defaultTargetMeanWaitTime * 1.5);

		List<SimpleFeature> features = new ShpOptions(drtOperationalArea, null, null).readFeatures();
		for (SimpleFeature feature : features) {
			double targetWaitTime = Double.parseDouble(feature.getAttribute(TYP_WT).toString());
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
					double typicalWaitTimeInZone = Double.parseDouble(feature.getAttribute(TYP_WT).toString());
					if (typicalWaitTimeInZone * 1.5 < maxWaitTime) {
						maxWaitTime = typicalWaitTimeInZone * 1.5;
					}
				}
			}
			return Optional.of(constraintsMap.get(maxWaitTime));
		}
	}
}
