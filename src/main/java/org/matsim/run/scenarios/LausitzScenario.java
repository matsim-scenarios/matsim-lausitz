package org.matsim.run.scenarios;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import com.google.common.collect.Sets;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.pt.PublicTransitAnalysis;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.emissions.HbefaVehicleCategory;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.run.analysis.CommunityFilter;
import org.matsim.run.analysis.CommuterAnalysis;
import org.matsim.run.analysis.DistanceMatrix;
import org.matsim.run.prepare.PrepareDrtScenarioAgents;
import org.matsim.run.prepare.PrepareNetwork;
import org.matsim.run.prepare.PreparePopulation;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CommandLine.Command(header = ":: Open Lausitz Scenario ::", version = LausitzScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
		CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class,
		MergePopulations.class, ExtractRelevantFreightTrips.class, DownSamplePopulation.class, ExtractHomeCoordinates.class, CleanNetwork.class,
		CreateLandUseShp.class, ResolveGridCoordinates.class, FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class,
		SplitActivityTypesDuration.class, CreateCountsFromBAStData.class, PreparePopulation.class, CleanPopulation.class, PrepareNetwork.class,
		PrepareDrtScenarioAgents.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class, CommuterAnalysis.class, CommunityFilter.class, DistanceMatrix.class, PublicTransitAnalysis.class
})
public class LausitzScenario extends MATSimApplication {
// user classes should be final or non-public.  This one here currently can neither be final nor non-public.  kai, jun'25

// This class is public and non-final intentionally.
// Public: Required because it's accessed from outside the package.
// Non-final: Required because it is extended by other scenario classes like LausitzPtScenario because we want to apply all changes of this (base) class
//	to all other (policy) cases.
//	One could think of making this class non-public and refactoring all functionality which is used by other classes to a utils class,
//	but we would have the same problem (public class needed). -sm0825

	public static final String VERSION = "2.0";
	public static final String FREIGHT = "longDistanceFreight";
	public static final String SLASH = "/";
	private static final String AVERAGE = "average";
	public static final String HEAVY_MODE = "truck40t";
	public static final String MEDIUM_MODE = "truck18t";
	public static final String LIGHT_MODE = "truck8t";
	public static final String CRS = "EPSG:25832";

//	To decrypt hbefa input files set MATSIM_DECRYPTION_PASSWORD as environment variable. ask VSP for access.
	private static final String HBEFA_2020_PATH = "https://svn.vsp.tu-berlin.de/repos/public-svn/3507bb3997e5657ab9da76dbedbb13c9b5991d3e/0e73947443d68f95202b71a156b337f7f71604ae/";
	private static final String HBEFA_FILE_COLD_DETAILED = HBEFA_2020_PATH + "82t7b02rc0rji2kmsahfwp933u2rfjlkhfpi2u9r20.enc";
	private static final String HBEFA_FILE_WARM_DETAILED = HBEFA_2020_PATH + "944637571c833ddcf1d0dfcccb59838509f397e6.enc";
	private static final String HBEFA_FILE_COLD_AVERAGE = HBEFA_2020_PATH + "r9230ru2n209r30u2fn0c9rn20n2rujkhkjhoewt84202.enc" ;
	private static final String HBEFA_FILE_WARM_AVERAGE = HBEFA_2020_PATH + "7eff8f308633df1b8ac4d06d05180dd0c5fdf577.enc";

	@CommandLine.Mixin
	SampleOptions sample = new SampleOptions( 100, 25, 10, 1);

	@CommandLine.Option(names = "--emissions", defaultValue = "ENABLED", description = "Define if emission analysis should be performed or not.")
	FunctionalityHandling emissions;
	@CommandLine.Option(names = "--explicit-walk-intermodality", defaultValue = "ENABLED", description = "Define if explicit walk intermodality parameter to/from pt should be set or not (use default).")
	static FunctionalityHandling explicitWalkIntermodality;

	public LausitzScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzScenario(String configPath) {
		super(configPath);
	}

	public LausitzScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", VERSION, VERSION));
	}

	public LausitzScenario(SampleOptions sample, FunctionalityHandling handling) {
		this.sample = sample;
		this.emissions = handling;
	}

	public static void main(String[] args) {
		MATSimApplication.run(LausitzScenario.class, args);
	}

	@Nullable
	@Override
	public Config prepareConfig(Config config) {

		// Add all activity types with time bins
		SnzActivities.addScoringParams(config);

//		add simwrapper config module
		SimWrapperConfigGroup simWrapper = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		if (sample.isSet()) {
			config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
			config.controller().setRunId(sample.adjustName(config.controller().getRunId()));

			config.qsim().setFlowCapFactor(sample.getSample());
			config.qsim().setStorageCapFactor(sample.getSample());
			config.counts().setCountsScaleFactor(sample.getSample());

			simWrapper.sampleSize = sample.getSample();
		}

//		performing set to 6.0 after calibration task force july 24
		double performing = 6.0;
		ScoringConfigGroup scoringConfigGroup = config.scoring();
		scoringConfigGroup.setPerforming_utils_hr(performing);
		scoringConfigGroup.setWriteExperiencedPlans(true);
		scoringConfigGroup.setPathSizeLogitBeta(0.);

//		set ride scoring params dependent from car params
//		2.0 + 1.0 = alpha + 1
//		ride cost = alpha * car cost
//		ride marg utility of traveling = (alpha + 1) * marg utility travelling car + alpha * beta perf
		double alpha = 2;
		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(scoringConfigGroup, alpha);

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);

		prepareCommercialTrafficConfig(config);

//		set pt fare calc model to fareZoneBased = fare of vvo tarifzone 20 is paid for trips within fare zone
//		every other trip: Deutschlandtarif
//		for more info see PTFareModule / ChainedPtFareCalculator classes in vsp contrib
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);

//		fare prices for vvo tarifzone 20 have to be set in shp file.
		FareZoneBasedPtFareParams vvo20 = new FareZoneBasedPtFareParams();
		vvo20.setTransactionPartner("VVO Tarifzone 20");
		vvo20.setDescription("VVO Tarifzone 20");
		vvo20.setOrder(1);
		vvo20.setFareZoneShp("./vvo_tarifzone20/vvo_tarifzone20_hoyerswerda_utm32n.shp");
		vvo20.setFareZoneShp(String.format("./vvo_tarifzone20/v%s_vvo_tarifzone20_hoyerswerda_utm32n.shp", VERSION));

		DistanceBasedPtFareParams germany = DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024;
		germany.setTransactionPartner("Deutschlandtarif");
		germany.setDescription("Deutschlandtarif");
		germany.setOrder(2);

//		apply inflation factor to distance based fare. fare values are from 10.12.23 / for the whole of 2024.
//		car cost in this scenario is projected to 2021. Hence, we deflate the pt cost to 2021
//		according to https://www-genesis.destatis.de/genesis/online?sequenz=tabelleErgebnis&selectionname=61111-0001&startjahr=1991#abreadcrumb (same source as for car cost inflation in google drive)
//		Verbraucherpreisindex 2021 to 2024: 103.1 to 119.3 = 16.2 = inflationFactor of 1.16
//		pt distance cost 2021: cost = (m*distance + b) / inflationFactor = m * inflationFactor * distance + b * inflationFactor
//		ergo: slope2021 = slope2024/inflationFactor and intercept2021 = intercept2024/inflationFactor
		double inflationFactor = 1.16;
		DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams below100km = germany.getOrCreateDistanceClassFareParams(100_000.);
		below100km.setFareSlope(below100km.getFareSlope() / inflationFactor);
		below100km.setFareIntercept(below100km.getFareIntercept() / inflationFactor);

		DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams greaterThan100km = germany.getOrCreateDistanceClassFareParams(Double.POSITIVE_INFINITY);
		greaterThan100km.setFareSlope(greaterThan100km.getFareSlope() / inflationFactor);
		greaterThan100km.setFareIntercept(greaterThan100km.getFareIntercept() / inflationFactor);

		ptFareConfigGroup.addParameterSet(vvo20);
		ptFareConfigGroup.addParameterSet(germany);

		if (explicitWalkIntermodality == FunctionalityHandling.ENABLED) {
			setExplicitIntermodalityParamsForWalkToPt(ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class));
		}

		if (emissions == FunctionalityHandling.ENABLED) {
//		set hbefa input files for emission analysis
			setEmissionsConfigs(config);
		}
		return config;
	}

	public static void setExplicitIntermodalityParamsForWalkToPt(SwissRailRaptorConfigGroup srrConfig) {
		srrConfig.setUseIntermodalAccessEgress(true);
		srrConfig.setIntermodalAccessEgressModeSelection(SwissRailRaptorConfigGroup.IntermodalAccessEgressModeSelection.CalcLeastCostModePerStop);

//			add walk as access egress mode to pt
		SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet accessEgressWalkParam = new SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
		accessEgressWalkParam.setMode(TransportMode.walk);
//			initial radius for pt stop search
		accessEgressWalkParam.setInitialSearchRadius(10000);
		accessEgressWalkParam.setMaxRadius(100000);
//			with this, initialSearchRadius gets extended by the set value until maxRadius is reached
		accessEgressWalkParam.setSearchExtensionRadius(1000);
		srrConfig.addIntermodalAccessEgress(accessEgressWalkParam);
	}

	@Override
	public void prepareScenario(Scenario scenario) {
//		add freight and truck as allowed modes together with car
		PrepareNetwork.prepareFreightNetwork(scenario.getNetwork());

		if (emissions == FunctionalityHandling.ENABLED) {
//			prepare hbefa link attributes + make link.getType() handable for OsmHbefaMapping
			PrepareNetwork.prepareEmissionsAttributes(scenario.getNetwork());
//			prepare vehicle types for emission analysis
			prepareVehicleTypesForEmissionAnalysis(scenario);
		}
	}

	@Override
	public void prepareControler(Controler controler) {

		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());

		controler.addOverridingModule(new SimWrapperModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(getPtFareModule());
				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();

				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

//				we do not need to add SwissRailRaptor explicitely! this is done in core
			}

		});
	}

	/**
	 * Prepare the config for commercial traffic.
	 */
	public static void prepareCommercialTrafficConfig(Config config) {

		Set<String> modes = Set.of(HEAVY_MODE, MEDIUM_MODE, LIGHT_MODE);

		modes.forEach(mode -> {
			ScoringConfigGroup.ModeParams thisModeParams = new ScoringConfigGroup.ModeParams(mode);
			config.scoring().addModeParams(thisModeParams);
		});

		Set<String> qsimModes = new HashSet<>(config.qsim().getMainModes());
		config.qsim().setMainModes(Sets.union(qsimModes, modes));

		Set<String> networkModes = new HashSet<>(config.routing().getNetworkModes());
		config.routing().setNetworkModes(Sets.union(networkModes, modes));

		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("start").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("end").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60.));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60.));

		for (String subpopulation : List.of("commercialPersonTraffic", "commercialPersonTraffic_service", "goodsTraffic")) {
			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
					.setWeight(0.85)
					.setSubpopulation(subpopulation)
			);

			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
					.setWeight(0.1)
					.setSubpopulation(subpopulation)
			);
		}
	}

	public static void setEmissionsConfigs(Config config) {
		EmissionsConfigGroup eConfig = ConfigUtils.addOrGetModule(config, EmissionsConfigGroup.class);
		eConfig.setDetailedColdEmissionFactorsFile(HBEFA_FILE_COLD_DETAILED);
		eConfig.setDetailedWarmEmissionFactorsFile(HBEFA_FILE_WARM_DETAILED);
		eConfig.setAverageColdEmissionFactorsFile(HBEFA_FILE_COLD_AVERAGE);
		eConfig.setAverageWarmEmissionFactorsFile(HBEFA_FILE_WARM_AVERAGE);
		eConfig.setHbefaTableConsistencyCheckingLevel(EmissionsConfigGroup.HbefaTableConsistencyCheckingLevel.consistent);
		eConfig.setDetailedVsAverageLookupBehavior(EmissionsConfigGroup.DetailedVsAverageLookupBehavior.tryDetailedThenTechnologyAverageThenAverageTable);
	}

	/**
	 * Prepare vehicle types with necessary HBEFA information for emission analysis.
	 */
	public static void prepareVehicleTypesForEmissionAnalysis(Scenario scenario) {
		for (VehicleType type : scenario.getVehicles().getVehicleTypes().values()) {
			EngineInformation engineInformation = type.getEngineInformation();

//				only set engine information if none are present
			if (engineInformation.getAttributes().isEmpty()) {
				switch (type.getId().toString()) {
					case TransportMode.car -> {
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.PASSENGER_CAR.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case TransportMode.ride -> {
//							ignore ride, the mode routed on network, but then teleported
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case FREIGHT -> {
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.HEAVY_GOODS_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					case TransportMode.bike -> {
//							ignore bikes
						VehicleUtils.setHbefaVehicleCategory(engineInformation, HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString());
						VehicleUtils.setHbefaTechnology(engineInformation, AVERAGE);
						VehicleUtils.setHbefaSizeClass(engineInformation, AVERAGE);
						VehicleUtils.setHbefaEmissionsConcept(engineInformation, AVERAGE);
					}
					default -> throw new IllegalArgumentException("does not know how to handle vehicleType " + type.getId().toString());
				}
			}
		}

//			ignore all pt veh types
		scenario.getTransitVehicles()
			.getVehicleTypes()
			.values().forEach(type -> VehicleUtils.setHbefaVehicleCategory(type.getEngineInformation(), HbefaVehicleCategory.NON_HBEFA_VEHICLE.toString()));
	}

//	overridable method to implement custom PtFareModules in policy scenarios.
	public AbstractModule getPtFareModule() {
		return new PtFareModule();
	}

	public static FunctionalityHandling getExplicitWalkIntermodalityInBase() {
		return explicitWalkIntermodality;
	}

	/**
	 * Helper enum to enable/disable functionalities.
	 */
	public enum FunctionalityHandling {ENABLED, DISABLED}
}
