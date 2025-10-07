package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.estimator.DrtEstimatorModule;
import org.matsim.contrib.drt.estimator.impl.DirectTripBasedDrtEstimator;
import org.matsim.contrib.drt.estimator.impl.distribution.NormalDistributionGenerator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.ConstantRideDurationEstimator;
import org.matsim.contrib.drt.estimator.impl.waiting_time_estimation.ShapeFileBasedWaitingTimeEstimator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.passenger.PassengerRequestValidator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.dashboards.LausitzDrtDashboard;
import org.matsim.drt.PtAndDrtFareModule;
import org.matsim.drt.ShpBasedDrtRequestValidator;
import org.matsim.run.DrtAndIntermodalityOptions;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperModule;
import picocli.CommandLine;

import javax.annotation.Nullable;

/**
 * Run the Lausitz scenario including a regional DRT service.
 * All necessary configs will be made in this class.
 */
public final class LausitzDrtScenario extends LausitzScenario {

	//	run params re drt are contained in separate class DrtOptions
	@CommandLine.ArgGroup(heading = "%nDrt options%n", exclusive = false, multiplicity = "0..1")
	private final DrtAndIntermodalityOptions drtOpt = new DrtAndIntermodalityOptions();
	@CommandLine.Option(names = "--base-run", description = "Path to run directory of base run. Used for comparison for dashboards/analysis.", defaultValue = ".")
	private String baseRunDir;

	private SimWrapper sw;

	//	this constructor is needed when this class is to be called from external classes with a given Config (e.g. for testing).
	public LausitzDrtScenario(Config config) {
		super(config);
	}

	public LausitzDrtScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(LausitzDrtScenario.class, args);
	}

	@Nullable
	@Override
	public Config prepareConfig(Config config) {
//		apply all config changes from base scenario class
		super.prepareConfig(config);

//		apply all necessary config changes for drt simulation
		drtOpt.configureDrtConfig(config);

		return config;
	}

	@Override
	public void prepareScenario(Scenario scenario) {
//		apply all scenario changes from base scenario class
		super.prepareScenario(scenario);

//		apply all necessary scenario changes for drt simulation
		drtOpt.configureDrtScenario(scenario);

//		add LausitzDrtDashboard. this cannot be done in DrtOptions as we need super.basePath.
		sw = SimWrapper.create(scenario.getConfig());
		sw.addDashboard(new LausitzDrtDashboard(baseRunDir,
			scenario.getConfig().global().getCoordinateSystem(), sw.getConfigGroup().sampleSize));
	}

	@Override
	public void prepareControler(Controler controler) {
		Config config = controler.getConfig();

		Scenario scenario = controler.getScenario();
		Network network = scenario.getNetwork();
		ShpOptions shp = new ShpOptions(IOUtils.extendUrl(config.getContext(), drtOpt.getDrtAreaShp()).toString(), null, null);

//		apply all controller changes from base scenario class
		super.prepareControler(controler);

		controler.addOverridingModule(new DvrpModule());
		controler.addOverridingModule(new MultiModeDrtModule());
//		simwrapper module already is added in LausitzScenario class
//		but we need the custom dashboard for this case, so we add it again. -sm05225
		controler.addOverridingModule(new SimWrapperModule(sw));

//		the following cannot be "experts only" (like requested from KN) because without it DRT would not work
//		here, the DynActivityEngine, PreplanningEngine + DvrpModule for each drt mode are added to the qsim components
//		this is necessary for drt / dvrp to work!
		// there is a syntax that can achieve the same thing but it does not need the "components". kai, jun'25
		controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(ConfigUtils.addOrGetModule(controler.getConfig(), MultiModeDrtConfigGroup.class)));

		MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
		for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					DrtEstimatorModule.bindEstimator(binder(), drtConfigGroup.mode).toInstance(
						new DirectTripBasedDrtEstimator.Builder()
//							TODO: for what exactly is the typicalWaitingTIme needed? Don't we set this from the shp file
							.setWaitingTimeEstimator(new ShapeFileBasedWaitingTimeEstimator(network, shp.readFeatures(), drtOpt.getTypicalWaitTime()))
							.setWaitingTimeDistributionGenerator(new NormalDistributionGenerator(1, drtOpt.getWaitTimeStd()))
							.setRideDurationEstimator(new ConstantRideDurationEstimator(drtOpt.getRideTimeAlpha(), drtOpt.getRideTimeBeta()))
							.setRideDurationDistributionGenerator(new NormalDistributionGenerator(2, drtOpt.getRideTimeStd()))
							.build()
					);
				}
			});

			// Overwrite the passenger request validator with the ShpBasedDrtRequestValidator
			controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtConfigGroup.mode) {
				@Override
				protected void configureQSim() {
					bindModal(PassengerRequestValidator.class).toProvider(
						modalProvider(getter -> new ShpBasedDrtRequestValidator(shp))).asEagerSingleton();
				}
			});
		}
	}


//	this method overrides the getPtFareModule method in LausitzScenario (parent class).
//	for DRT we need an upperBoundHandler which gives fare refunds when using pt OR drt.
//	the handler is added in PtAndDrtFareModule. -sm0525
	@Override
	public AbstractModule getPtFareModule() {
		if (drtOpt.getFareHandling() == LausitzScenario.FunctionalityHandling.ENABLED) {
			return new PtAndDrtFareModule();
		} else {
			return new PtFareModule();
		}
	}
}
