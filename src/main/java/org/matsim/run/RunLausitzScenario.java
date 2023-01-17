package org.matsim.run;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.common.collect.Sets;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.analysis.travelTimeValidation.TravelTimeAnalysis;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

@CommandLine.Command(header = ":: Open Lausitz Scenario ::", version = RunLausitzScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
		CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class,
		MergePopulations.class, ExtractRelevantFreightTrips.class, DownSamplePopulation.class, ExtractHomeCoordinates.class, CleanNetwork.class,
		CreateLandUseShp.class, ResolveGridCoordinates.class, FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class,
		CreateCountsFromBAStData.class
})
@MATSimApplication.Analysis({
		TravelTimeAnalysis.class, LinkStats.class, CheckPopulation.class
})
public class RunLausitzScenario extends MATSimApplication {

	static final String VERSION = "1.0";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(100, 25, 1);


	public RunLausitzScenario(@Nullable Config config) {
		super(config);
	}

	public RunLausitzScenario() {
		super(String.format("input/v%s/lausitz-v%s-100pct.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunLausitzScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins

		for (long ii = 600; ii <= 97200; ii += 600) {

			for (String act : List.of("home", "restaurant", "other", "visit", "errands", "accomp_other", "accomp_children",
					"educ_higher", "educ_secondary", "educ_primary", "educ_tertiary", "educ_kiga", "educ_other")) {
				config.planCalcScore()
						.addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams(act + "_" + ii).setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("work_" + ii).setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("business_" + ii).setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("leisure_" + ii).setTypicalDuration(ii)
					.setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_daily_" + ii).setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_other_" + ii).setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("car interaction").setTypicalDuration(60));
		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("other").setTypicalDuration(600 * 3));

		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_start").setTypicalDuration(60 * 15));
		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_end").setTypicalDuration(60 * 15));

		config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));
		config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
		config.controler().setRunId(sample.adjustName(config.controler().getRunId()));

		config.qsim().setFlowCapFactor(sample.getSize() / 100.0);
		config.qsim().setStorageCapFactor(sample.getSize() / 100.0);

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);
		config.plansCalcRoute().setAccessEgressType(PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLink);

		// TODO: Config options

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

				link.setAllowedModes(newModes);
			}
		}

	}

	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new SwissRailRaptorModule());

				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);
				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

			}

		});
	}
}