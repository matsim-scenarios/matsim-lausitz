package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.LausitzExtendedOpeningTimesActivitiesScenario;

/**
 * Run the Lausitz scenario with extended opening times for acts.
 */
public final class RunLausitzExtendedOpeningTimesActivitiesScenario {

	private RunLausitzExtendedOpeningTimesActivitiesScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzExtendedOpeningTimesActivitiesScenario.class, args);
	}

}
