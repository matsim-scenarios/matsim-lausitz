package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.run.prepare.LausitzSnzActivities;

import javax.annotation.Nullable;

/**
 * Lausitz scenario including extended act opening times based on the act arrival times from v2.0 base case ctd.
 * All necessary configs will be made in this class.
 */
public class LausitzExtendedOpeningTimesActivitiesScenario extends LausitzScenario {
	Logger log = LogManager.getLogger(LausitzExtendedOpeningTimesActivitiesScenario.class);

	public LausitzExtendedOpeningTimesActivitiesScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzExtendedOpeningTimesActivitiesScenario(@Nullable String args) {
		super(args);
	}

	public LausitzExtendedOpeningTimesActivitiesScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
	}

	@Override
	protected void addScoringParams(Config config) {
		// yyyy need to find a way to remove the existing scoring params; then this can be programmed without inheritance
//		use class LausitzSnzActivities for extended opening times based on v2.0 base case ctd act arrivals.
		LausitzSnzActivities.addScoringParams(config);
	}

	@Nullable
	@Override
	public Config prepareConfig(Config config) {
		//		apply all config changes from base scenario class
		super.prepareConfig(config);

		config.timeAllocationMutator().setLatestActivityEndTime(String.valueOf(config.qsim().getEndTime().seconds()));
		config.timeAllocationMutator().setMutateAroundInitialEndTimeOnly(false);
		config.timeAllocationMutator().setAffectingDuration(false);

		return config;
	}

	@Override
	public void prepareScenario(Scenario scenario) {
		//		apply all scenario changes from base scenario class
		super.prepareScenario(scenario);
	}

	@Override
	public void prepareControler(Controler controler) {
		//		apply all controller changes from base scenario class
		super.prepareControler(controler);
	}
}
