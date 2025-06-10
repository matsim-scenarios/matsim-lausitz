package org.matsim.run.drtpostsimulation;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import com.google.common.base.Preconditions;
import com.google.inject.Singleton;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.analysis.zonal.DrtZoneSystemParams;
import org.matsim.contrib.drt.optimizer.constraints.ConstraintSetChooser;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.run.*;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.utils.CreateFleetVehicles;
import org.matsim.utils.objectattributes.attributable.Attributes;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.matsim.contrib.drt.analysis.afterSimAnalysis.DrtVehicleStoppingTaskWriter.glob;
import static org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme.serviceAreaBased;

/**
 * Run DRT post-simulation to acquire KPIs of DRT operations.
 */
public class RunDrtPostSimulation implements MATSimAppCommand {
	Logger log = LogManager.getLogger(RunDrtPostSimulation.class);
	@CommandLine.Option(names = "--main-sim-output", description = "path to the output folder of main simulation (complete path)", required = true)
	private Path mainSimOutputPath;
	@CommandLine.Option(names = "--fleet-sizing", description = "a triplet: [from max interval]. ", arity = "1..*", defaultValue = "10 30 5")
	private List<Integer> fleetSizing;
	@CommandLine.Option(names = "--capacity", description = "vehicle capacity", defaultValue = "8")
	private String vehicleCapacity;
	@CommandLine.Option(names = "--target-mean-wait-time", description = "target mean wait time by default, can be overwritten by the values in shp", defaultValue = "600")
	private int defaultTargetMeanWaitTime;
	@CommandLine.Option(names = "--start-time", description = "service starting time", defaultValue = "0")
	private double startTime;
	@CommandLine.Option(names = "--end-time", description = "service ending time", defaultValue = "129600")
	private double endTime;
	@CommandLine.Option(names = "--service-area-path", description = "path to the output folder of main simulation (complete path)", required = true)
	private Path drtServiceAreaPath;
	@CommandLine.Option(names = "--network-mode", description = "Network mode for drt simulation.", defaultValue = TransportMode.car)
	private String networkMode;

	private static final String TYP_WT = "typ_wt";
	private static final String POST_SIM_DIR = "drt-post-simulation";

	public static void main(String[] args) {
		new RunDrtPostSimulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {
//		extract drt plans if they do no exist
		Path drtPlansPath = Paths.get(mainSimOutputPath.toString(), POST_SIM_DIR).resolve("drt-plans.xml.gz");
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
		ShpOptions shp = new ShpOptions(drtServiceAreaPath.toAbsolutePath().normalize(), null, null);
		List<SimpleFeature> features = shp.readFeatures();

		// Run simulations and analyze output
		for (int fleetSize = fleetFrom; fleetSize <= fleetMax; fleetSize += fleetInterval) {
			// setup run with specific fleet size
			String outputDirectory = Paths.get(drtPlansPath.getParent().toString(), fleetSize + "-veh").toString();
			String configPath = glob(mainSimOutputPath, "*output_config.xml")
				.orElseThrow(() -> new NoSuchElementException("The main output directory does not have an output config file."))
				.toString();

			ScoringConfigGroup scoring = new ScoringConfigGroup();
			ScoringConfigGroup.ActivityParams dummyActParams = scoring.getActivityParams("dummy");
			dummyActParams.setScoringThisActivityAtAll(false);
			dummyActParams.setTypicalDuration(24 * 3600.);
			dummyActParams.setTypicalDurationScoreComputation(ScoringConfigGroup.TypicalDurationScoreComputation.relative);

			Config newConfig = new Config();
			newConfig.addModule(scoring);

//			apparently one cannot replace a configGroup in the config with a custom one and also cannot
//			delete act params and mode params from scoringCfgGroup.
//			thus, we need to create an empty config container, add the custom cfgGroups and then copy over the ones we need from the output cfg
			Config oldConfig = ConfigUtils.loadConfig(configPath);

			Set<String> excludedCfgGroups = Set.of(EmissionsConfigGroup.GROUP_NAME, ScoringConfigGroup.GROUP_NAME, PtFareConfigGroup.MODULE_NAME,
				SwissRailRaptorConfigGroup.GROUP);
//			add all cfg groups which we want to use from output cfg from main run
			for (ConfigGroup group : oldConfig.getModules().values()) {
				if (excludedCfgGroups.contains(group.getName())) {
					continue;
				}
				newConfig.addModule(group);
			}

			DvrpConfigGroup dvrpCfg = ConfigUtils.addOrGetModule(newConfig, DvrpConfigGroup.class);
			dvrpCfg.networkModes = Set.of(networkMode);
			ConfigUtils.addOrGetModule(newConfig, SimWrapperConfigGroup.class);

			MultiModeDrtConfigGroup multiModeDrtConfigGroup = ConfigUtils.addOrGetModule(newConfig, MultiModeDrtConfigGroup.class);
//			make sure there is only 1 drt mode (should always be the case in this scenario)
			if (multiModeDrtConfigGroup.getModalElements().size() > 1) {
				log.fatal("There should be no more than 1 drt mode in the MultiModeDrtConfigGroup! The loaded config group has {} drt modes!",
					multiModeDrtConfigGroup.getModalElements().size());
				throw new IllegalStateException();
			}
			DrtConfigGroup drtCfg = multiModeDrtConfigGroup.getModalElements().stream().findFirst().orElse(null);

			assert drtCfg != null;
			if (!drtCfg.getMode().equals(TransportMode.drt)) {
				log.fatal("The name of the drt mode should be {}! The loaded config group configures a drt mode with name {} instead!",
					TransportMode.drt, drtCfg.getMode());
				throw new IllegalStateException();
			}

			//		define CreateFleetVehicles object to generate drt veh fleet
			CreateFleetVehicles fleetGenerator = new CreateFleetVehicles(Integer.parseInt(vehicleCapacity), drtCfg.getMode(), startTime,
				endTime, "", shp, newConfig.network().getInputFile(), mainSimOutputPath);

			String[] outputVehiclePaths = fleetGenerator.generateFleetWithSpecifiedParams(fleetSize, fleetGenerator.getAllowedStartLinks(), newConfig.controller().getRunId(), networkMode);

			drtCfg.vehiclesFile = outputVehiclePaths[0];
			drtCfg.drtServiceAreaShapeFile = shp.getShapeFile();
			drtCfg.operationalScheme = serviceAreaBased;
			drtCfg.simulationType = DrtConfigGroup.SimulationType.fullSimulation;

			addSpecialDrtParametersets(drtCfg, shp);

			adaptNewConfig(newConfig, outputDirectory, drtPlansPath, oldConfig, outputVehiclePaths[1]);

			Controler controler = DrtControlerCreator.createControler(newConfig, false);
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

	private void addSpecialDrtParametersets(DrtConfigGroup drtCfg, ShpOptions shp) {
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
			drtConstraintSet.maxWalkDistance = 1000.;
			drtOptimizationConstraintsParams.addParameterSet(drtConstraintSet);
		}

		RebalancingParams rebalancingParams = new RebalancingParams();
		rebalancingParams.interval = 300;
		rebalancingParams.maxTimeBeforeIdle = 300.;
		MinCostFlowRebalancingStrategyParams minCostFlow = new MinCostFlowRebalancingStrategyParams();
		minCostFlow.targetAlpha = 0.25;
		minCostFlow.targetBeta = 0.75;
		rebalancingParams.addParameterSet(minCostFlow);
		DrtZoneSystemParams zoneSystemParams = drtCfg.getZonalSystemParams().orElse(new DrtZoneSystemParams());
		SquareGridZoneSystemParams squareGridZone = new SquareGridZoneSystemParams();
		squareGridZone.cellSize = 2000.;
		zoneSystemParams.addParameterSet(squareGridZone);

		drtCfg.addParameterSet(rebalancingParams);
		drtCfg.addParameterSet(zoneSystemParams);
	}

	private void adaptNewConfig(Config newConfig, String outputDirectory, Path drtPlansPath, Config oldConfig, String outputVehTypesPath) throws IOException {
		newConfig.controller().setOutputDirectory(outputDirectory);
		newConfig.controller().setLastIteration(1);
		newConfig.counts().setInputFile(null);
		newConfig.global().setCoordinateSystem("EPSG:25832");
		newConfig.plans().setInputFile(drtPlansPath.toString());
//			set flow/storage cap factors to 1 (as in 100pct) because we only look at drt vehicles anyways
		newConfig.qsim().setFlowCapFactor(1.);
		newConfig.qsim().setStorageCapFactor(1.);
		newConfig.qsim().setMainModes(Set.of(networkMode));
		newConfig.replanningAnnealer().setActivateAnnealingModule(false);
		newConfig.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);
		newConfig.routing().setNetworkModes(Set.of(networkMode));
		newConfig.transit().setUseTransit(false);
		newConfig.transit().setTransitScheduleFile(null);
		newConfig.transit().setVehiclesFile(null);
		newConfig.vehicles().setVehiclesFile(outputVehTypesPath);

		//			get output network, filter it for drt only and set as input network if it does not exist yet
		Path drtNetworkPath = Paths.get(mainSimOutputPath.toString(), POST_SIM_DIR).resolve("drt-network.xml.gz");
		if (!Files.exists(drtNetworkPath)) {
//			make sure all parent dirs exist
			Files.createDirectories(drtNetworkPath.getParent());
			String networkPath = glob(mainSimOutputPath, "*output_network.xml.gz")
				.orElseThrow(() -> new NoSuchElementException("The main output directory does not have an output network file."))
				.toString();

			Network network = NetworkUtils.readNetwork(networkPath);
			NetworkFilterManager filter = new NetworkFilterManager(network, newConfig.network());
			filter.addLinkFilter(link -> link.getAllowedModes().contains(TransportMode.drt));
			Network drtNetwork = filter.applyFilters();

			NetworkUtils.writeNetwork(drtNetwork, drtNetworkPath.toString());
		}
		newConfig.network().setInputFile(drtNetworkPath.toString());

		ReplanningConfigGroup replanning = newConfig.replanning();
//			clear all replanning strategies except ChangeExpBeta
		ReplanningConfigGroup.StrategySettings changeExpBeta = replanning.getStrategySettings().stream()
			.filter(s -> s.getSubpopulation().equals("person"))
			.filter(s -> s.getStrategyName().equals("ChangeExpBeta"))
			.findAny()
			.orElseThrow(() -> new NoSuchElementException("The config file does not have a replanning strategy ChangeExpBeta for subpopulation person." +
				"Please check if you used the correct config file or use another one."));

		changeExpBeta.setWeight(1.);
		replanning.clearStrategySettings();
		replanning.addStrategySettings(changeExpBeta);

//			copy mode params from oldConfig even though we do not need them here.
//			this is done to avoid confusion when looking at the drtPostSimCfg or comparing it to the usual cfg. -sm0525
		for (Map.Entry<String, ScoringConfigGroup.ModeParams> entry : newConfig.scoring().getModes().entrySet()) {
			String key = entry.getKey();
			ScoringConfigGroup.ModeParams newParams = entry.getValue();
			ScoringConfigGroup.ModeParams oldParams = oldConfig.scoring().getModes().get(key);

			if (oldParams == null) {
				continue;
			}
			newParams.setConstant(oldParams.getConstant());
			newParams.setDailyMonetaryConstant(oldParams.getDailyMonetaryConstant());
			newParams.setDailyUtilityConstant(oldParams.getDailyUtilityConstant());
			newParams.setMarginalUtilityOfDistance(oldParams.getMarginalUtilityOfDistance());
			newParams.setMarginalUtilityOfTraveling(oldParams.getMarginalUtilityOfTraveling());
			newParams.setMonetaryDistanceRate(oldParams.getMonetaryDistanceRate());
		}

//			make sure drt mode params exist in scoring params
		if (newConfig.scoring().getModes().get(TransportMode.drt) == null) {
			newConfig.scoring().addModeParams(oldConfig.scoring().getModes().get(TransportMode.drt));
		}
		newConfig.scoring().setFractionOfIterationsToStartScoreMSA(0.9);
		newConfig.scoring().setPathSizeLogitBeta(0.);
		newConfig.scoring().setWriteExperiencedPlans(true);
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
