package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.drt.estimator.DrtEstimatorModule;
import org.matsim.contrib.drt.estimator.impl.DirectTripBasedDrtEstimator;
import org.matsim.contrib.drt.estimator.impl.distribution.NormalDistributionGenerator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.ConstantRideDurationEstimator;
import org.matsim.contrib.drt.estimator.impl.waiting_time_estimation.ConstantWaitingTimeEstimator;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.run.DrtOptions;
import picocli.CommandLine;

import javax.annotation.Nullable;

/**
 * Run the Lausitz scenario including a regional DRT service.
 * All necessary configs will be made in this class.
 */
public final class LausitzDrtScenario extends LausitzScenario {

//	run params re drt are contained in separate class DrtOptions
	@CommandLine.ArgGroup(heading = "%nDrt options%n", exclusive = false, multiplicity = "0..1")
	private final DrtOptions drtOpt = new DrtOptions();

	private final LausitzScenario baseScenario = new LausitzScenario(sample, emissions);

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
		baseScenario.prepareConfig(config);

//		apply all necessary config changes for drt simulation
		drtOpt.configureDrtConfig(config);

		return config;
	}

	@Override
	public void prepareScenario(Scenario scenario) {
//		apply all scenario changes from base scenario class
		baseScenario.prepareScenario(scenario);

//		apply all necessary scenario changes for drt simulation
		drtOpt.configureDrtScenario(scenario);
	}

	@Override
	public void prepareControler(Controler controler) {
		Config config = controler.getConfig();

//		apply all controller changes from base scenario class
		baseScenario.prepareControler(controler);

		controler.addOverridingModule(new DvrpModule());
		controler.addOverridingModule(new MultiModeDrtModule());

//		the following cannot be "experts only" (like requested from KN) because without it DRT would not work
//		here, the DynActivityEngine, PreplanningEngine + DvrpModule for each drt mode are added to the qsim components
//		this is necessary for drt / dvrp to work!
		controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(ConfigUtils.addOrGetModule(controler.getConfig(), MultiModeDrtConfigGroup.class)));

		MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
		for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					DrtEstimatorModule.bindEstimator(binder(), drtConfigGroup.mode).toInstance(
						new DirectTripBasedDrtEstimator.Builder()
							.setWaitingTimeEstimator(new ConstantWaitingTimeEstimator(drtOpt.getTypicalWaitTime()))
							.setWaitingTimeDistributionGenerator(new NormalDistributionGenerator(1, drtOpt.getWaitTimeStd()))
							.setRideDurationEstimator(new ConstantRideDurationEstimator(drtOpt.getRideTimeAlpha(), drtOpt.getRideTimeBeta()))
							.setRideDurationDistributionGenerator(new NormalDistributionGenerator(2, drtOpt.getRideTimeStd()))
							.build()
					);
				}
			});
		}

	}
}
