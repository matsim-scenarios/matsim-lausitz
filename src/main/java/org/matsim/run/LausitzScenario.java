package org.matsim.run;

import com.google.common.collect.Sets;
import com.google.inject.Key;
import com.google.inject.name.Names;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.run.analysis.CommuterAnalysis;
import org.matsim.run.prepare.PreparePopulation;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
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
		SplitActivityTypesDuration.class, CreateCountsFromBAStData.class, PreparePopulation.class, CleanPopulation.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class, CommuterAnalysis.class,
})
public class LausitzScenario extends MATSimApplication {

	public static final String VERSION = "1.1";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions( 100, 25, 10, 1);


	public LausitzScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzScenario(String configPath) {
		super(configPath);
	}

	public LausitzScenario() {
		super(String.format("input/v1.1/lausitz-v1.1-10pct.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(LausitzScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

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

//		set ride scoring params dependent from car params
		ScoringConfigGroup.ModeParams rideParams = scoringConfigGroup.getOrCreateModeParams(TransportMode.ride);
		ScoringConfigGroup.ModeParams carParams = scoringConfigGroup.getModes().get(TransportMode.car);
//		2.0 + 1.0 = alpha + 1
//		ride cost = alpha * car cost
//		ride marg utility of traveling = (alpha + 1) * marg utility travelling car + alpha * beta perf
		double alpha = 2;
		rideParams.setMarginalUtilityOfTraveling((alpha + 1) * carParams.getMarginalUtilityOfTraveling() - alpha * config.scoring().getPerforming_utils_hr());
		rideParams.setDailyMonetaryConstant(0.);
		rideParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate() * 2);

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);

		prepareCommercialTrafficConfig(config);
		// TODO: Config options

		// TODO: recreate counts format with car and trucks

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		for (Link link : scenario.getNetwork().getLinks().values()) {
			Set<String> modes = link.getAllowedModes();

			// allow freight traffic together with cars
			if (modes.contains("car")) {
				Set<String> newModes = Sets.newHashSet(modes);
				newModes.add("freight");
				newModes.add("truck");

				link.setAllowedModes(newModes);
			}
		}
	}

	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new SimWrapperModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new LausitzPtFareModule());

				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();

				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

				addTravelTimeBinding("freight").to(Key.get(TravelTime.class, Names.named(TransportMode.truck)));
				addTravelDisutilityFactoryBinding("freight").to(Key.get(TravelDisutilityFactory.class, Names.named(TransportMode.truck)));
//				we do not need to add SwissRailRaptor explicitely! this is done in core
			}

		});
	}

	/**
	 * Prepare the config for commercial traffic.
	 */
	public static void prepareCommercialTrafficConfig(Config config) {

		Set<String> modes = Set.of("freight", "truck");

		modes.forEach(mode -> {
			ScoringConfigGroup.ModeParams thisModeParams = new ScoringConfigGroup.ModeParams(mode);
			config.scoring().addModeParams(thisModeParams);
		});

		Set<String> qsimModes = new HashSet<>(config.qsim().getMainModes());
		config.qsim().setMainModes(Sets.union(qsimModes, modes));

		Set<String> networkModes = new HashSet<>(config.routing().getNetworkModes());
		config.routing().setNetworkModes(Sets.union(networkModes, modes));

		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("start").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("end").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60));

		//TODO: add freight and remove from config or change freight subpopulation and mode for long distance freight
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
}
